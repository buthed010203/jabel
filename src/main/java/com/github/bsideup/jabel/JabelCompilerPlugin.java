package com.github.bsideup.jabel;

import com.sun.source.util.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.parser.*;
import net.bytebuddy.*;
import net.bytebuddy.agent.*;
import net.bytebuddy.asm.*;
import net.bytebuddy.dynamic.loading.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;

import static net.bytebuddy.matcher.ElementMatchers.*;
import sun.misc.Unsafe;

public class JabelCompilerPlugin implements Plugin{

    static final Set<Source.Feature> ENABLED_FEATURES = Stream.of(
        "PRIVATE_SAFE_VARARGS",

        "SWITCH_EXPRESSION",
        "SWITCH_RULE",
        "SWITCH_MULTIPLE_CASE_LABELS",

        "LOCAL_VARIABLE_TYPE_INFERENCE",
        "VAR_SYNTAX_IMPLICIT_LAMBDAS",

        "DIAMOND_WITH_ANONYMOUS_CLASS_CREATION",

        "EFFECTIVELY_FINAL_VARIABLES_IN_TRY_WITH_RESOURCES",

        "TEXT_BLOCKS",

        "PATTERN_MATCHING_IN_INSTANCEOF",
        "REIFIABLE_TYPES_INSTANCEOF"
    ).map(name -> {
        try{
            return Source.Feature.valueOf(name);
        }catch(IllegalArgumentException e){
            return null;
        }
    })
    .filter(Objects::nonNull)
    .collect(Collectors.toSet());

    static Unsafe unsafe = null;

    //Disable Java 9 warnings re "An illegal reflective access operation has occurred"
    //code taken from Manifold
    static void disableJava9IllegalAccessWarning(ClassLoader cl){
        try{
            Class cls = Class.forName("jdk.internal.module.IllegalAccessLogger", false, cl);
            Field logger = cls.getDeclaredField("logger");
            getUnsafe().putObjectVolatile(cls, getUnsafe().staticFieldOffset(logger), null);
        }catch(Throwable ignore){}
    }

    static Unsafe getUnsafe(){
        if(unsafe != null) return unsafe;

        try{
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return unsafe = (Unsafe)theUnsafe.get(null);
        }catch(Throwable t){
            throw new RuntimeException("The 'Unsafe' class is not accessible");
        }
    }

    @Override
    public void init(JavacTask task, String... args){
        //runtime
        disableJava9IllegalAccessWarning(JabelCompilerPlugin.class.getClassLoader());
        //compile-time
        disableJava9IllegalAccessWarning(Thread.currentThread().getContextClassLoader());

        ByteBuddyAgent.install();

        ByteBuddy byteBuddy = new ByteBuddy();

        for(Class<?> clazz : Arrays.asList(JavacParser.class, JavaTokenizer.class)){
            byteBuddy
            .redefine(clazz)
            .visit(
            Advice.to(CheckSourceLevelAdvice.class)
            .on(named("checkSourceLevel").and(takesArguments(2)))
            )
            .make()
            .load(clazz.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
        }

        try{
            Field field = Source.Feature.class.getDeclaredField("minLevel");
            field.setAccessible(true);

            for(Source.Feature feature : ENABLED_FEATURES){
                field.set(feature, Source.JDK8);
                if(!feature.allowedInSource(Source.JDK8)){
                    throw new IllegalStateException(feature.name() + " minLevel instrumentation failed!");
                }
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }

        if(Arrays.asList(args).contains("printFeatures")){
            System.out.println(
            ENABLED_FEATURES.stream()
            .map(Enum::name)
            .collect(Collectors.joining("\n\t- ", "Jabel: initialized. Enabled features: \n\t- ", "\n")));
        }
    }

    @Override
    public String getName(){
        return "jabel";
    }

    // Make it auto start on Java 14+
    public boolean autoStart(){
        return true;
    }
}
