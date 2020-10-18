package com.github.bsideup.jabel;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Source.*;
import net.bytebuddy.asm.*;

class CheckSourceLevelAdvice{

    @Advice.OnMethodEnter
    static void checkSourceLevel(@Advice.Argument(value = 1, readOnly = false) Feature feature){
        if(feature.allowedInSource(Source.JDK8)){
            //noinspection UnusedAssignment
            feature = Source.Feature.LAMBDA;
        }
    }
}
