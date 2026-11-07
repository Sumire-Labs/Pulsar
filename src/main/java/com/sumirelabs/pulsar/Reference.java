package com.sumirelabs.pulsar;

/**
 * Tags storage class. Values are populated at compile time by TokenEnvoy from Git and
 * gradle.properties.
 */
public final class Reference {

    public static final String MOD_ID = "@{MOD_ID}";
    public static final String MOD_NAME = "@{MOD_NAME}";
    public static final String VERSION = "@{MOD_VERSION}";
    private Reference() {}

}
