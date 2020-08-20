package org.jetbrains.kotlinx.lincheck;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import org.jetbrains.kotlinx.lincheck.runner.*;
import org.jetbrains.kotlinx.lincheck.strategy.*;
import org.jetbrains.kotlinx.lincheck.strategy.managed.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.jetbrains.kotlinx.lincheck.TransformationClassLoader.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * This transformer applies required for {@link Strategy} and {@link Runner}
 * class transformations and hines them from others.
 */
public class TransformationClassLoader extends ExecutionClassLoader {
    private final List<Function<ClassVisitor, ClassVisitor>> classTransformers;
    // Cache for classloading and frames computing during the transformation.
    private final Map<String, Class<?>> cache = new ConcurrentHashMap<>();
    private final Remapper remapper;

    public static final String TRANSFORMED_PACKAGE_INTERNAL_NAME = "org/jetbrains/kotlinx/lincheck/tran$f*rmed/";
    public static final String TRANSFORMED_PACKAGE_NAME = TRANSFORMED_PACKAGE_INTERNAL_NAME.replace('/', '.');
    public static final int ASM_API = ASM7;

    public TransformationClassLoader(Strategy strategy, Runner runner) {
        classTransformers = new ArrayList<>();
        // Apply the strategy's transformer at first, then the runner's one.
        if (strategy.needsTransformation()) classTransformers.add(strategy::createTransformer);
        if (runner.needsTransformation()) classTransformers.add(runner::createTransformer);
        remapper = strategy.createRemapper();
    }

    public TransformationClassLoader(Function<ClassVisitor, ClassVisitor> classTransformer) {
        this.classTransformers = Collections.singletonList(classTransformer);
        remapper = null;
    }

    /**
     * Returns `true` if the specified class should not be transformed.
     */
    private static boolean doNotTransform(String className) {
        if (className.startsWith(TRANSFORMED_PACKAGE_NAME)) return false;
        if (TrustedAtomicPrimitivesKt.isImpossibleToTransformPrimitive(className)) return true;

        return className.startsWith("sun.") ||
            className.startsWith("java.") ||
            className.startsWith("jdk.internal.") ||
            (className.startsWith("kotlin.") &&
                !className.startsWith("kotlin.collections.") && // transform kotlin collections
                !(className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) && // transform kotlin iterator classes
                !className.startsWith("kotlin.ranges")) || // transform kotlin ranges
            (className.startsWith("org.jetbrains.kotlinx.lincheck.") &&
                !className.startsWith("org.jetbrains.kotlinx.lincheck.test.") &&
                !className.equals(ManagedStateHolder.class.getName())) ||
            className.equals(kotlinx.coroutines.CancellableContinuation.class.getName()) ||
            className.equals(kotlinx.coroutines.CoroutineDispatcher.class.getName());
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> result = cache.get(name);
            if (result != null) {
                return result;
            }
            if (doNotTransform(name)) {
                result = super.loadClass(name);
                cache.put(name, result);
                return result;
            }
            try {
                byte[] bytes = instrument(originalName(name));
                result = defineClass(name, bytes, 0, bytes.length);
                cache.put(name, result);
                return result;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot transform class " + name, e);
            }
        }
    }

    /**
     * Reads class as resource, instruments it (applies {@link Strategy}'s transformer at first,
     * then {@link Runner}'s) and returns the resulting byte-code.
     *
     * @param className name of the class to be transformed.
     * @return the byte-code of the transformed class.
     * @throws IOException if class could not be read as a resource.
     */
    private byte[] instrument(String className) throws IOException {
        // Create ClassReader
        ClassReader cr = new ClassReader(className);
        // Construct transformation pipeline:
        // apply the strategy's transformer at first,
        // then the runner's one.
        ClassVersionGetter infoGetter = new ClassVersionGetter();
        cr.accept(infoGetter, 0);
        ClassWriter cw = new TransformationClassWriter(infoGetter.getClassVersion(), remapper);
        ClassVisitor cv = new CheckClassAdapter(cw, false); // for debug
        for (Function<ClassVisitor, ClassVisitor> ct : classTransformers)
            cv = ct.apply(cv);
        // Get transformed bytecode
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * Returns name of class the moment before it was transformed
     */
    private String originalName(String className) {
        if (className.startsWith(TRANSFORMED_PACKAGE_NAME))
            return className.substring(TRANSFORMED_PACKAGE_NAME.length());
        return className;
    }

    @Override
    public URL getResource(String name) {
        return super.getResource(name);
    }
}

/**
 * ClassWriter for the classes transformed by *lincheck* with a correct
 * {@link ClassWriter#getCommonSuperClass} implementation.
 */
class TransformationClassWriter extends ClassWriter {
    private final Remapper remapper;

    public TransformationClassWriter(int classVersion, Remapper remapper) {
        super(classVersion > V1_6 ? COMPUTE_FRAMES : COMPUTE_MAXS);
        this.remapper = remapper;
    }

    /**
     * ASM uses Class.forName for given types, however it can lead to cyclic dependencies when loading transformed classes.
     * Thus, we call original method for not-transformed class names and then just fix it if needed.
     */
    @Override
    protected String getCommonSuperClass(final String type1, final String type2) {
        String result = super.getCommonSuperClass(originalInternalName(type1), originalInternalName(type2));
        if (remapper != null)
            return remapper.map(result);
        return result;
    }

    /**
     * Returns name of class the moment before it was transformed
     */
    private String originalInternalName(String className) {
        if (className.startsWith(TRANSFORMED_PACKAGE_INTERNAL_NAME))
            return className.substring(TRANSFORMED_PACKAGE_INTERNAL_NAME.length());
        return className;
    }
}

/**
 * Visitor for retrieving information of class version needed for choosing between COMPUTE_FRAME and COMPUTE_MAXS
 */
class ClassVersionGetter extends ClassVisitor {
    private int classVersion;

    public ClassVersionGetter() {
        super(TransformationClassLoader.ASM_API);
    }

    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.classVersion = version;
    }

    public int getClassVersion() {
        return classVersion;
    }
}