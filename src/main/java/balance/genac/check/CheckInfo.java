package balance.genac.check;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CheckInfo {

    String name();

    CheckType type();

    String description() default "";

    boolean enabled() default true;

    int maxViolations() default 10;

    boolean experimental() default false;
}