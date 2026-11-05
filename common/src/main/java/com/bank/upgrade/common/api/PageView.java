package com.bank.upgrade.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** One page of a list endpoint, the same shape everywhere so the ops console has one pager. */
public record PageView<T>(List<T> items, int page, int size, long total) {

    public static <S, T> PageView<T> of(Page<S> page, Function<S, T> map) {
        return new PageView<>(page.getContent().stream().map(map).toList(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
