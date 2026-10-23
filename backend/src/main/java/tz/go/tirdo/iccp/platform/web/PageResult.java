package tz.go.tirdo.iccp.platform.web;

import java.util.List;

/** A page of results with the total count, for list endpoints. */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static int clampSize(Integer size) {
        if (size == null || size < 1) {
            return 25;
        }
        return Math.min(size, 100);
    }

    public static int clampPage(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }
}
