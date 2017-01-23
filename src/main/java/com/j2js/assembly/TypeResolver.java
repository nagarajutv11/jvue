package com.j2js.assembly;

import com.j2js.J2JSCompiler;
import com.j2js.Log;
import com.j2js.Parser;
import com.j2js.dom.TypeDeclaration;
import com.j2js.visitors.Generator;

public class TypeResolver implements TypeVisitor {

    private Generator generator;

    private Project project;

    private J2JSCompiler compiler;

    public TypeResolver(J2JSCompiler compiler, Project theProject,
            Generator theGenerator) {
        this.compiler = compiler;
        project = theProject;
        generator = theGenerator;
    }

    public void visit(ClassUnit clazz) {
        if (clazz.isResolved())
            return;

        Log logger = Log.getLogger();

        if (clazz.getSignature().toString().startsWith("[")) {
            // Class is an array class without class file: Do nothing.
        } else if (!clazz.isUpToDate()) {
            clazz.clear();
            try {
                compile(clazz);
                compiler.compileCount++;
            } catch (RuntimeException ex) {
                J2JSCompiler.errorCount++;
                logger.error(ex.toString());
                // ex.printStackTrace();
                if (compiler.failOnError) {
                    throw ex;
                }
            }
        } else {
            logger.debug("Up to date: " + clazz);
        }

        clazz.setResolved(true);
    }

    /**
     * Compiles the unit.
     */
    private void compile(ClassUnit classUnit) {

        if (classUnit.getClassFile() == null) {
            Log.getLogger().warn("Cannot read " + classUnit.getClassFile());
            return;
        }

        Log.getLogger().info("Cross-Compiling " + classUnit);

        TypeDeclaration typeDecl = parse(classUnit);

        // TODO
        // if (!Modifier.isInterface(typeDecl.getAccess())) {
        typeDecl.visit(generator);
        // }

        // Set not current date but date of last modification. This is
        // independent of system clock.
        classUnit.setLastCompiled(classUnit.getLastModified());
    }

    private TypeDeclaration parse(ClassUnit classUnit) {
        Parser parser = new Parser(classUnit, compiler);
        TypeDeclaration typeDecl = parser.parse();

        return typeDecl;
    }
}
