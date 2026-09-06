package com.lightquery;

import com.lightquery.entity.User;
import com.lightquery.exception.MappingException;
import com.lightquery.lambda.LambdaUtils;
import com.lightquery.lambda.SFunction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** T2 — lambda property resolution. */
class LambdaUtilsTest {

    @Test
    void resolvesGetters() {
        assertEquals("name", LambdaUtils.getPropertyName(User::getName));
        assertEquals("id", LambdaUtils.getPropertyName(User::getId));
        assertEquals("status", LambdaUtils.getPropertyName(User::getStatus));
    }

    @Test
    void implClassIsTheEntity() {
        assertEquals(User.class, LambdaUtils.getImplClass(User::getName));
    }

    @Test
    void rejectsNonGetters() {
        SFunction<User, Integer> notAGetter = User::hashCode;
        // hashCode() has no get/is prefix
        assertThrows(MappingException.class, () -> LambdaUtils.getPropertyName(notAGetter));
    }

    @Test
    void cachesRepeatedResolution() {
        // same call-site lambda is interned by the JVM: resolving twice hits the cache
        SFunction<User, String> getter = User::getName;
        LambdaUtils.getPropertyName(getter);
        assertEquals("name", LambdaUtils.getPropertyName(getter));
    }

    @Test
    void resolvesLambdaTypedAsSFunction() {
        // lambdas typed directly as SFunction carry the serialized form the
        // resolver needs; Function-typed ones would not (that is expected)
        SFunction<User, String> getter = User::getName;
        assertEquals("name", LambdaUtils.getPropertyName(getter));
    }
}
