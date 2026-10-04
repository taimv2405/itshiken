package com.edu.material.repository;

import com.edu.material.entity.LearningMaterial;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearningMaterialRepository extends JpaRepository<LearningMaterial, Long> {

    Page<LearningMaterial> findByCategory(String category, Pageable pageable);

    Page<LearningMaterial> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    Page<LearningMaterial> findByTitleContainingIgnoreCaseAndCategory(String title, String category, Pageable pageable);

    Page<LearningMaterial> findByType(String type, Pageable pageable);

    List<LearningMaterial> findTop10ByOrderByCreatedAtDesc();
}
