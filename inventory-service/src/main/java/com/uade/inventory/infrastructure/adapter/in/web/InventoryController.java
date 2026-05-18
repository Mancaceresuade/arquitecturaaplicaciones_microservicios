package com.uade.inventory.infrastructure.adapter.in.web;

import com.uade.inventory.domain.model.Product;
import com.uade.inventory.domain.port.in.ProductUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;


import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final ProductUseCase productUseCase;
    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    public InventoryController(ProductUseCase productUseCase) {
        this.productUseCase = productUseCase;
    }

    @GetMapping("/products") 
    public ResponseEntity<List<Product>> getAllProducts() { 
        List<Product> products = productUseCase.getAllProducts(); 
        log.info("GET /api/inventory/products → {} producto(s)", products.size()); 
        return ResponseEntity.ok(products); 
    }
    
    @GetMapping("/products/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        return productUseCase.getProductById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/products")
    public ResponseEntity<Product> createProduct(@RequestBody Product product) {
        return ResponseEntity.ok(productUseCase.createProduct(product));
    }
}
