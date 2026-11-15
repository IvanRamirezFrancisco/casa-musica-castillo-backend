package com.security.service.alexa;

import com.security.dto.alexa.AlexaCategoryDTO;
import com.security.repository.PublicCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlexaCategoryQueryService {

    private final PublicCategoryRepository publicCategoryRepository;

    public List<AlexaCategoryDTO> getCategories() {
        return publicCategoryRepository.findActiveWithProducts()
                .stream()
                .filter(c -> c.getName() != null && !c.getName().toLowerCase().contains("prueba") && !c.getName().toLowerCase().contains("test"))
                .map(c -> new AlexaCategoryDTO(c.getId(), c.getName(), c.getDescription() != null ? c.getDescription() : "Categoría de " + c.getName()))
                .collect(Collectors.toList());
    }
}
