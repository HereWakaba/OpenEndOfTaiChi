package com.ryjs.agent.transformers.bypass;

import java.util.Set;


public interface CleanMethodBypass {
    Set<String> protectedClasses();
    Set<String> protectedMethods();
}
