package com.orinan.db.naverconnection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NaverConnectionRepository extends JpaRepository<NaverConnectionEntity, Long> {
    List<NaverConnectionEntity> findAllBySolutionSubscriptionId(Long subscriptionId);
}
