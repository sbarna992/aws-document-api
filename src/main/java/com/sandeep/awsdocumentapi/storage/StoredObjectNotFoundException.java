package com.sandeep.awsdocumentapi.storage;

public class StoredObjectNotFoundException extends RuntimeException {

    public StoredObjectNotFoundException(String storageKey) {
        super("No stored object for key '" + storageKey + "'");
    }
}
