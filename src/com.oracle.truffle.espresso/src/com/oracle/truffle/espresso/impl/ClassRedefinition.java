/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.impl;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.espresso.bytecode.BytecodeStream;
import com.oracle.truffle.espresso.bytecode.Bytecodes;
import com.oracle.truffle.espresso.classfile.ClassfileParser;
import com.oracle.truffle.espresso.classfile.ClassfileStream;
import com.oracle.truffle.espresso.classfile.ConstantPool;
import com.oracle.truffle.espresso.classfile.attributes.CodeAttribute;
import com.oracle.truffle.espresso.classfile.attributes.LineNumberTableAttribute;
import com.oracle.truffle.espresso.classfile.attributes.Local;
import com.oracle.truffle.espresso.classfile.attributes.LocalVariableTable;
import com.oracle.truffle.espresso.classfile.constantpool.PoolConstant;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.jdwp.api.ErrorCodes;
import com.oracle.truffle.espresso.jdwp.api.Ids;
import com.oracle.truffle.espresso.jdwp.api.KlassRef;
import com.oracle.truffle.espresso.jdwp.api.RedefineInfo;
import com.oracle.truffle.espresso.runtime.Attribute;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public final class ClassRedefinition {

    @CompilationFinal static volatile RedefineAssumption current = new RedefineAssumption();

    private static final Object redefineLock = new Object();
    private static volatile boolean locked = false;
    private static Thread redefineThread = null;

    public static void begin() {
        // the redefine thread is privileged
        redefineThread = Thread.currentThread();
        locked = true;
        current.assumption.invalidate();
    }

    public static void end() {
        synchronized (redefineLock) {
            locked = false;
            current = new RedefineAssumption();
            redefineThread = null;
            redefineLock.notifyAll();
        }
    }

    private static class RedefineAssumption {
        private final Assumption assumption = Truffle.getRuntime().createAssumption();
    }

    public static void check() {
        RedefineAssumption ra = current;
        if (!ra.assumption.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (redefineThread == Thread.currentThread()) {
                // let the redefine thread pass
                return;
            }
            // block until redefinition is done
            synchronized (redefineLock) {
                while (locked) {
                    try {
                        redefineLock.wait();
                    } catch (InterruptedException e) {
                        Thread.interrupted();
                    }
                }
            }
            // re-check in case a new redefinition was kicked off
            check();
        }
    }

    private enum RedefinitionSupport {
        METHOD_BODY,
        ADD_METHOD,
        REMOVE_METHOD,
        ARBITRARY
    }

    enum ClassChange {
        NO_CHANGE,
        METHOD_BODY_CHANGE,
        ADD_METHOD,
        SCHEMA_CHANGE,
        HIERARCHY_CHANGE,
        REMOVE_METHOD,
        CLASS_MODIFIERS_CHANGE,
        METHOD_MODIFIERS_CHANGE,
        CONSTANT_POOL_CHANGE,
        INVALID
    }

    private static final RedefinitionSupport REDEFINITION_SUPPORT = RedefinitionSupport.REMOVE_METHOD;

    public static List<ChangePacket> detectClassChanges(RedefineInfo[] redefineInfos, EspressoContext context) {
        List<ChangePacket> result = new ArrayList<>(redefineInfos.length);
        for (RedefineInfo redefineInfo : redefineInfos) {
            KlassRef klass = redefineInfo.getKlass();
            byte[] bytes = redefineInfo.getClassBytes();
            ParserKlass parserKlass = ClassfileParser.parse(new ClassfileStream(bytes, null), klass.getTypeAsString(), null, context);
            ClassChange classChange;
            DetectedChange detectedChange = new DetectedChange();
            if (klass instanceof ObjectKlass) {
                ObjectKlass objectKlass = (ObjectKlass) klass;
                ParserKlass oldParserKlass = objectKlass.getLinkedKlass().getParserKlass();
                classChange = detectClassChanges(parserKlass, oldParserKlass, detectedChange);
            } else {
                // array or primitive klass, should never happen
                classChange = ClassChange.INVALID;
            }
            result.add(new ChangePacket(redefineInfo, parserKlass, classChange, detectedChange));
        }
        return result;
    }

    public static int redefineClass(ChangePacket packet, Ids<Object> ids, List<ObjectKlass> refreshSubClasses) {
        try {
            switch (packet.classChange) {
                case METHOD_BODY_CHANGE:
                case CONSTANT_POOL_CHANGE:
                    return doRedefineClass(packet, ids, refreshSubClasses);
                case ADD_METHOD:
                    if (isAddMethodSupported()) {
                        return doRedefineClass(packet, ids, refreshSubClasses);
                    } else {
                        return ErrorCodes.ADD_METHOD_NOT_IMPLEMENTED;
                    }
                case REMOVE_METHOD:
                    if (isRemoveMethodSupported()) {
                        return doRedefineClass(packet, ids, refreshSubClasses);
                    } else {
                        return ErrorCodes.DELETE_METHOD_NOT_IMPLEMENTED;
                    }
                case METHOD_MODIFIERS_CHANGE:
                    if (isArbitraryChangesSupported()) {
                        return doRedefineClass(packet, ids, refreshSubClasses);
                    } else {
                        return ErrorCodes.METHOD_MODIFIERS_CHANGE_NOT_IMPLEMENTED;
                    }
                case SCHEMA_CHANGE:
                    if (isArbitraryChangesSupported()) {
                        return doRedefineClass(packet, ids, refreshSubClasses);
                    } else {
                        return ErrorCodes.SCHEMA_CHANGE_NOT_IMPLEMENTED;
                    }
                case CLASS_MODIFIERS_CHANGE:
                    if (isArbitraryChangesSupported()) {
                        return doRedefineClass(packet, ids, refreshSubClasses);
                    } else {
                        return ErrorCodes.CLASS_MODIFIERS_CHANGE_NOT_IMPLEMENTED;
                    }
                case HIERARCHY_CHANGE:
                    if (isArbitraryChangesSupported()) {
                        return doRedefineClass(packet, ids, refreshSubClasses);
                    } else {
                        return ErrorCodes.HIERARCHY_CHANGE_NOT_IMPLEMENTED;
                    }
                default:
                    return 0;
            }
        } catch (EspressoException ex) {
            // TODO(Gregersen) - return appropriate error code based on the exception type
            // we get from parsing the class file
            return ErrorCodes.INVALID_CLASS_FORMAT;
        }
    }

    private static boolean isArbitraryChangesSupported() {
        return REDEFINITION_SUPPORT == RedefinitionSupport.ARBITRARY;
    }

    private static boolean isAddMethodSupported() {
        return REDEFINITION_SUPPORT == RedefinitionSupport.ADD_METHOD || isRemoveMethodSupported() || isArbitraryChangesSupported();
    }

    private static boolean isRemoveMethodSupported() {
        return REDEFINITION_SUPPORT == RedefinitionSupport.REMOVE_METHOD || isArbitraryChangesSupported();
    }

    // detect all types of class changes, but return early when a change that require arbitrary
    // changes
    private static ClassChange detectClassChanges(ParserKlass newParserKlass, ParserKlass oldParserKlass, DetectedChange collectedChanges) {
        ClassChange result = ClassChange.NO_CHANGE;
        // detect class-level changes
        if (newParserKlass.getFlags() != oldParserKlass.getFlags()) {
            return ClassChange.CLASS_MODIFIERS_CHANGE;
        }

        if (!newParserKlass.getSuperKlass().equals(oldParserKlass.getSuperKlass()) || !Arrays.equals(newParserKlass.getSuperInterfaces(), oldParserKlass.getSuperInterfaces())) {
            return ClassChange.HIERARCHY_CHANGE;
        }

        // detect field changes
        ParserField[] oldFields = oldParserKlass.getFields();
        ParserField[] newFields = newParserKlass.getFields();

        if (oldFields.length != newFields.length) {
            return ClassChange.SCHEMA_CHANGE;
        }

        for (int i = 0; i < oldFields.length; i++) {
            ParserField oldField = oldFields[i];
            // verify that there is a new corresponding field
            boolean found = false;
            for (int j = 0; j < newFields.length; j++) {
                ParserField newField = newFields[j];
                if (isUnchangedField(oldField, newField)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return ClassChange.SCHEMA_CHANGE;
            }
        }

        // detect method changes (including constructors)
        List<ParserMethod> oldMethods = new ArrayList<>(Arrays.asList(oldParserKlass.getMethods()));
        List<ParserMethod> newMethods = new ArrayList<>(Arrays.asList(newParserKlass.getMethods()));

        if (ClassRedefinition.REDEFINITION_SUPPORT == RedefinitionSupport.METHOD_BODY) {
            // we only need to hunt down method bodies changes then
            // so return immediately when we see an added/removed method
            if (oldMethods.size() < newMethods.size()) {
                return ClassChange.ADD_METHOD;
            }
            if (oldMethods.size() > newMethods.size()) {
                return ClassChange.REMOVE_METHOD;
            }
        }

        boolean constantPoolChanged = false;
        // check constant pool changes. If changed, we have to redefine all methods in the class
        if (!Arrays.equals(oldParserKlass.getConstantPool().getRawBytes(), newParserKlass.getConstantPool().getRawBytes())) {
            constantPoolChanged = true;
        }
        Iterator<ParserMethod> oldIt = oldMethods.iterator();
        while (oldIt.hasNext()) {
            ParserMethod oldMethod = oldIt.next();
            // verify that there is a new corresponding method
            Iterator<ParserMethod> newIt = newMethods.iterator();
            while (newIt.hasNext()) {
                ParserMethod newMethod = newIt.next();
                if (isSameMethod(oldMethod, newMethod)) {
                    // detect method changes
                    ClassChange change = detectMethodChanges(oldMethod, newMethod);
                    switch (change) {
                        case NO_CHANGE:
                            if (constantPoolChanged) {
                                if (isObsolete(oldMethod, newMethod, oldParserKlass.getConstantPool(), newParserKlass.getConstantPool())) {
                                    result = ClassChange.CONSTANT_POOL_CHANGE;
                                    collectedChanges.addMethodBodyChange(newMethod);
                                }
                            }
                            break;
                        case METHOD_BODY_CHANGE:
                            result = change;
                            collectedChanges.addMethodBodyChange(newMethod);
                            break;
                        case METHOD_MODIFIERS_CHANGE:
                            // not handled yet
                            return change;
                        default:
                            return change;
                    }
                    newIt.remove();
                    oldIt.remove();
                    break;
                }
            }
        }
        collectedChanges.addNewMethods(newMethods);
        collectedChanges.addRemovedMethods(oldMethods);

        if (!oldMethods.isEmpty()) {
            result = ClassChange.REMOVE_METHOD;
        } else if (!newMethods.isEmpty()) {
            result = ClassChange.ADD_METHOD;
        }
        return result;
    }

    private static boolean isObsolete(ParserMethod oldMethod, ParserMethod newMethod, ConstantPool oldPool, ConstantPool newPool) {
        CodeAttribute oldCodeAttribute = (CodeAttribute) oldMethod.getAttribute(Symbol.Name.Code);
        CodeAttribute newCodeAttribute = (CodeAttribute) newMethod.getAttribute(Symbol.Name.Code);

        BytecodeStream oldCode = new BytecodeStream(oldCodeAttribute.getOriginalCode());
        BytecodeStream newCode = new BytecodeStream(newCodeAttribute.getOriginalCode());

        return !isSame(oldCode, oldPool, newCode, newPool);
    }

    private static boolean isSame(BytecodeStream oldCode, ConstantPool oldPool, BytecodeStream newCode, ConstantPool newPool) {
        int bci;
        int nextBCI = 0;
        while (nextBCI < oldCode.endBCI()) {
            bci = nextBCI;
            int opcode = oldCode.currentBC(bci);
            nextBCI = oldCode.nextBCI(bci);
            if (opcode == Bytecodes.LDC || opcode == Bytecodes.LDC2_W || opcode == Bytecodes.LDC_W || opcode == Bytecodes.NEW || opcode == Bytecodes.INVOKEDYNAMIC || Bytecodes.isInvoke(opcode)) {
                int oldCPI = oldCode.readCPI(bci);
                PoolConstant oldConstant = oldPool.at(oldCPI);
                int newCPI = newCode.readCPI(bci);
                PoolConstant newConstant = newPool.at(newCPI);
                if (!oldConstant.toString(oldPool).equals(newConstant.toString(newPool))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ClassChange detectMethodChanges(ParserMethod oldMethod, ParserMethod newMethod) {
        if (oldMethod.getFlags() != newMethod.getFlags()) {
            return ClassChange.METHOD_MODIFIERS_CHANGE;
        }
        // check method attributes that would constitute a higher-level
        // class redefinition than a method body change
        if (checkAttribute(oldMethod, newMethod, Symbol.Name.RuntimeVisibleTypeAnnotations)) {
            return ClassChange.SCHEMA_CHANGE;
        }

        if (checkAttribute(oldMethod, newMethod, Symbol.Name.RuntimeInvisibleTypeAnnotations)) {
            return ClassChange.SCHEMA_CHANGE;
        }

        if (checkAttribute(oldMethod, newMethod, Symbol.Name.Signature)) {
            return ClassChange.SCHEMA_CHANGE;
        }

        if (checkAttribute(oldMethod, newMethod, Symbol.Name.Exceptions)) {
            return ClassChange.SCHEMA_CHANGE;
        }

        // check code attribute
        CodeAttribute oldCodeAttribute = (CodeAttribute) oldMethod.getAttribute(Symbol.Name.Code);
        CodeAttribute newCodeAttribute = (CodeAttribute) newMethod.getAttribute(Symbol.Name.Code);

        if (!Arrays.equals(oldCodeAttribute.getOriginalCode(), newCodeAttribute.getOriginalCode())) {
            return ClassChange.METHOD_BODY_CHANGE;
        }
        // check line number table
        if (checkLineNumberTable(oldCodeAttribute.getLineNumberTableAttribute(), newCodeAttribute.getLineNumberTableAttribute())) {
            return ClassChange.METHOD_BODY_CHANGE;
        }

        // check local variable table
        if (checkLocalVariableTable(oldCodeAttribute.getLocalvariableTable(), newCodeAttribute.getLocalvariableTable())) {
            return ClassChange.METHOD_BODY_CHANGE;
        }

        // check local variable type table
        if (checkLocalVariableTable(oldCodeAttribute.getLocalvariableTypeTable(), newCodeAttribute.getLocalvariableTypeTable())) {
            return ClassChange.METHOD_BODY_CHANGE;
        }

        return ClassChange.NO_CHANGE;
    }

    private static boolean checkLineNumberTable(LineNumberTableAttribute table1, LineNumberTableAttribute table2) {
        LineNumberTableAttribute.Entry[] oldEntries = table1.getEntries();
        LineNumberTableAttribute.Entry[] newEntries = table2.getEntries();

        if (oldEntries.length != newEntries.length) {
            return true;
        }

        for (int i = 0; i < oldEntries.length; i++) {
            LineNumberTableAttribute.Entry oldEntry = oldEntries[i];
            LineNumberTableAttribute.Entry newEntry = newEntries[i];
            if (oldEntry.getLineNumber() != newEntry.getLineNumber() || oldEntry.getBCI() != newEntry.getBCI()) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkLocalVariableTable(LocalVariableTable table1, LocalVariableTable table2) {
        Local[] oldLocals = table1.getLocals();
        Local[] newLocals = table2.getLocals();

        if (oldLocals.length != newLocals.length) {
            return true;
        }

        for (int i = 0; i < oldLocals.length; i++) {
            Local oldLocal = oldLocals[i];
            Local newLocal = newLocals[i];
            if (!oldLocal.getNameAsString().equals(newLocal.getNameAsString()) || oldLocal.getSlot() != newLocal.getSlot() || oldLocal.getStartBCI() != newLocal.getStartBCI() ||
                            oldLocal.getEndBCI() != newLocal.getEndBCI()) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkAttribute(ParserMethod oldMethod, ParserMethod newMethod, Symbol<Symbol.Name> name) {
        Attribute oldAttribute = oldMethod.getAttribute(name);
        Attribute newAttribute = newMethod.getAttribute(name);
        if ((oldAttribute == null || newAttribute == null)) {
            if (oldAttribute != null || newAttribute != null) {
                return true;
            } // else both null, so no change. Move on!
        } else if (!Arrays.equals(oldAttribute.getData(), newAttribute.getData())) {
            return true;
        }
        return false;
    }

    private static boolean isSameMethod(ParserMethod oldMethod, ParserMethod newMethod) {
        return oldMethod.getName().equals(newMethod.getName()) && oldMethod.getSignature().equals(newMethod.getSignature());
    }

    private static boolean isUnchangedField(ParserField oldField, ParserField newField) {
        boolean same = oldField.getName().equals(newField.getName()) && oldField.getType().equals(newField.getType()) && oldField.getFlags() == newField.getFlags();

        if (same) {
            // check field attributes
            Attribute[] oldAttributes = oldField.getAttributes();
            Attribute[] newAttributes = newField.getAttributes();

            if (oldAttributes.length != newAttributes.length) {
                return false;
            }

            for (Attribute oldAttribute : oldAttributes) {
                boolean found = false;
                for (Attribute newAttribute : newAttributes) {
                    if (oldAttribute.getName().equals(newAttribute.getName()) && Arrays.equals(oldAttribute.getData(), newAttribute.getData())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static int doRedefineClass(ChangePacket packet, Ids<Object> ids, List<ObjectKlass> refreshSubClasses) {
        ObjectKlass oldKlass = (ObjectKlass) packet.info.getKlass();
        oldKlass.redefineClass(packet, refreshSubClasses, ids);
        return 0;
    }
}
