package com.niumi.coffeejournal.core.database

class DataIntegrityException(field: String, value: String) :
    IllegalStateException("Invalid persisted value '$value' for $field")
