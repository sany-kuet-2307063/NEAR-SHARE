package com.nearshare;

import java.util.List;

/**
 * Generic contract for a CRUD-capable data store.
 * DatabaseManager implements this for HistoryEntry records, which is what
 * lets the rest of the app talk to "some storage" without caring whether
 * it's SQLite, an in-memory list, or anything else (classic dependency
 * inversion via an interface).
 *
 * @param <T> the type of record this repository manages
 */
public interface Repository<T> {

    /** Create: persist a brand new record. */
    void create(T item);

    /** Read: return every record, newest first. */
    List<T> readAll();

    /** Update: persist changes made to an existing record. */
    void update(T item);

    /** Delete: remove a single record by its primary key. */
    void delete(int id);
}
