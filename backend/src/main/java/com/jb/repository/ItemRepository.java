package com.jb.repository;

import com.jb.domain.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByActiveTrueOrderBySortOrderAsc();
    List<Item> findAllByOrderBySortOrderAsc();
}
