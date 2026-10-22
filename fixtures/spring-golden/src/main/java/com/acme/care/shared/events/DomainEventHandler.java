package com.acme.care.shared.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.event.EventListener;

/**
 * Marks a synchronous handler for an in-process domain event. Handlers run on the
 * publisher's thread and inside the publisher's transaction, if any.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EventListener
public @interface DomainEventHandler {
}
