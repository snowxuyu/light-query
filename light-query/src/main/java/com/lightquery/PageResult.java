package com.lightquery;

import java.util.List;

/**
 * Offset pagination result of {@code Queryable#toPageResult(pageNo, pageSize)}.
 *
 * @param rows     the rows of the requested page
 * @param total    total matching rows (ignoring pagination)
 * @param pageNo   1-based page number
 * @param pageSize rows per page
 */
public record PageResult<T>(List<T> rows, long total, long pageNo, long pageSize) {

    public PageResult {
        rows = List.copyOf(rows);
    }

    public static <T> PageResult<T> of(List<T> rows, long total, long pageNo, long pageSize) {
        return new PageResult<>(rows, total, pageNo, pageSize);
    }

    /** Total number of pages; 0 when there is no data. */
    public long pages() {
        if (total == 0 || pageSize <= 0) {
            return 0;
        }
        return (total + pageSize - 1) / pageSize;
    }
}
