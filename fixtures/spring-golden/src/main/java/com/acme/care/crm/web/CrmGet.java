package com.acme.care.crm.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Read-only CRM endpoint returning JSON. {@link #value()} is the path relative to the
 * controller's base path.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RequestMapping(method = RequestMethod.GET, produces = "application/json")
public @interface CrmGet {

    @AliasFor(annotation = RequestMapping.class, attribute = "path")
    String[] value() default {};
}
