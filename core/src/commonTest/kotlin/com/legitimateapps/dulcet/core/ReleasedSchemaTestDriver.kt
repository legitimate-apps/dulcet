package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver

/**
 * A private in-memory database created from a RELEASED schema's DDL rather than the current one, so
 * a migration test drives the real `.sqm` files over a database shaped exactly as a shipped build
 * left it. Same isolation contract as [createTestDriver]: every call is its own database.
 */
internal expect fun createReleasedSchemaTestDriver(statements: List<String>): SqlDriver
