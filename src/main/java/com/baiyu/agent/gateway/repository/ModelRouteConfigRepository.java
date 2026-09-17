package com.baiyu.agent.gateway.repository;

import com.baiyu.agent.gateway.entity.ModelRouteConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModelRouteConfigRepository extends JpaRepository<ModelRouteConfig, String> {

    Optional<ModelRouteConfig> findByRouteKey(String routeKey);
}
