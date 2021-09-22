package com.github.bsideup.jabel;

import com.sun.source.util.*;
import com.sun.tools.javac.code.*;
import net.bytebuddy.*;
import net.bytebuddy.agent.*;
import net.bytebuddy.asm.*;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.*;
import net.bytebuddy.description.type.*;
import net.bytebuddy.dynamic.*;
import net.bytebuddy.dynamic.loading.*;
import net.bytebuddy.dynamic.scaffold.*;
import net.bytebuddy.implementation.*;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.*;
import net.bytebuddy.utility.*;

import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class JabelCompilerPlugin implements Plugin{
    static{
        Map<String, AsmVisitorWrapper> visitors = new HashMap<String, AsmVisitorWrapper>(){{
            // Disable the preview feature check
            AsmVisitorWrapper checkSourceLevelAdvice = Advice.to(CheckSourceLevelAdvice.class)
            .on(named("checkSourceLevel").and(takesArguments(2)));

            // Allow features that were introduced together with Records (local enums, static inner members, ...)
            AsmVisitorWrapper allowRecordsEraFeaturesAdvice = new FieldAccessStub("allowRecords", true);

            put("com.sun.tools.javac.parser.JavacParser",
            new AsmVisitorWrapper.Compound(
            checkSourceLevelAdvice,
            allowRecordsEraFeaturesAdvice
            )
            );
            put("com.sun.tools.javac.parser.JavaTokenizer", checkSourceLevelAdvice);

            put("com.sun.tools.javac.comp.Check", allowRecordsEraFeaturesAdvice);
            put("com.sun.tools.javac.comp.Attr", allowRecordsEraFeaturesAdvice);
            put("com.sun.tools.javac.comp.Resolve", allowRecordsEraFeaturesAdvice);

            // Lower the source requirement for supported features
            put(
            "com.sun.tools.javac.code.Source$Feature",
            Advice.to(AllowedInSourceAdvice.class)
            .on(named("allowedInSource").and(takesArguments(1)))
            );
        }};

        try{
            ByteBuddyAgent.install();
        }catch(Exception e){
            ByteBuddyAgent.install(
                new ByteBuddyAgent.AttachmentProvider.Compound(
                    ByteBuddyAgent.AttachmentProvider.ForJ9Vm.INSTANCE,
                    ByteBuddyAgent.AttachmentProvider.ForStandardToolsJarVm.JVM_ROOT,
                    ByteBuddyAgent.AttachmentProvider.ForStandardToolsJarVm.JDK_ROOT,
                    ByteBuddyAgent.AttachmentProvider.ForStandardToolsJarVm.MACINTOSH,
                    ByteBuddyAgent.AttachmentProvider.ForUserDefinedToolsJar.INSTANCE,
                    ByteBuddyAgent.AttachmentProvider.ForEmulatedAttachment.INSTANCE
                )
            );
        }

        ByteBuddy byteBuddy = new ByteBuddy()
            .with(MethodGraph.Compiler.ForDeclaredMethods.INSTANCE);

        ClassLoader classLoader = JavacTask.class.getClassLoader();
        ClassFileLocator classFileLocator = ClassFileLocator.ForClassLoader.of(classLoader);
        TypePool typePool = TypePool.ClassLoading.of(classLoader);

        visitors.forEach((className, visitor) -> {
            byteBuddy
            .decorate(
            typePool.describe(className).resolve(),
            classFileLocator
            )
            .visit(visitor)
            .make()
            .load(classLoader, ClassReloadingStrategy.fromInstalledAgent());
        });

        JavaModule jabelModule = JavaModule.ofType(JabelCompilerPlugin.class);
        ClassInjector.UsingInstrumentation.redefineModule(
        ByteBuddyAgent.getInstrumentation(),
        JavaModule.ofType(JavacTask.class),
        Collections.emptySet(),
        Collections.emptyMap(),
        new HashMap<String, Set<JavaModule>>(){{
            put("com.sun.tools.javac.api", Collections.singleton(jabelModule));
            put("com.sun.tools.javac.tree", Collections.singleton(jabelModule));
            put("com.sun.tools.javac.code", Collections.singleton(jabelModule));
            put("com.sun.tools.javac.util", Collections.singleton(jabelModule));
        }},
        Collections.emptySet(),
        Collections.emptyMap()
        );
    }

    @Override
    public void init(JavacTask task, String... args){

    }

    @Override
    public String getName(){
        return "jabel";
    }

    // Make it auto start on Java 14+
    public boolean autoStart(){
        return true;
    }

    static class AllowedInSourceAdvice{

        @Advice.OnMethodEnter
        static void allowedInSource(
        @Advice.This Source.Feature feature,
        @Advice.Argument(value = 0, readOnly = false) Source source
        ){
            switch(feature.name()){
                case "PRIVATE_SAFE_VARARGS":
                case "SWITCH_EXPRESSION":
                case "SWITCH_RULE":
                case "SWITCH_MULTIPLE_CASE_LABELS":
                case "LOCAL_VARIABLE_TYPE_INFERENCE":
                case "VAR_SYNTAX_IMPLICIT_LAMBDAS":
                case "DIAMOND_WITH_ANONYMOUS_CLASS_CREATION":
                case "EFFECTIVELY_FINAL_VARIABLES_IN_TRY_WITH_RESOURCES":
                case "TEXT_BLOCKS":
                case "PATTERN_MATCHING_IN_INSTANCEOF":
                case "REIFIABLE_TYPES_INSTANCEOF":
                //note that records aren't supported here, use the original jabel repo for that
                //case "RECORDS":
                    //noinspection UnusedAssignment
                    source = Source.DEFAULT;
                    break;
            }
        }
    }

    static class CheckSourceLevelAdvice{

        @Advice.OnMethodEnter
        static void checkSourceLevel(
        @Advice.Argument(value = 1, readOnly = false) Source.Feature feature
        ){
            if(feature.allowedInSource(Source.JDK8)){
                //noinspection UnusedAssignment
                feature = Source.Feature.LAMBDA;
            }
        }
    }

    private static class FieldAccessStub extends AsmVisitorWrapper.AbstractBase{
        final String fieldName;

        final Object value;

        public FieldAccessStub(String fieldName, Object value){
            this.fieldName = fieldName;
            this.value = value;
        }

        @Override
        public ClassVisitor wrap(TypeDescription instrumentedType, ClassVisitor classVisitor, Implementation.Context implementationContext, TypePool typePool, FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods, int writerFlags, int readerFlags){
            return new ClassVisitor(Opcodes.ASM9, classVisitor){
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions){
                    MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, methodVisitor){
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor){
                            if(opcode == Opcodes.GETFIELD && fieldName.equalsIgnoreCase(name)){
                                super.visitInsn(Opcodes.POP);
                                super.visitLdcInsn(value);
                            }else{
                                super.visitFieldInsn(opcode, owner, name, descriptor);
                            }
                        }
                    };
                }
            };
        }
    }
}