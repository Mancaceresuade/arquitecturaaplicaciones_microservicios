package com.uade.inventory.application.service;

import com.uade.inventory.domain.event.ProductCreatedEvent;
import com.uade.inventory.domain.model.Product;
import com.uade.inventory.domain.port.in.ProductUseCase;
import com.uade.inventory.domain.port.out.EventPublisherPort;
import com.uade.inventory.domain.port.out.ProductRepositoryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService implements ProductUseCase {

    private final ProductRepositoryPort repositoryPort;
    private final EventPublisherPort eventPublisherPort;
    private final MeterRegistry meterRegistry;

    // ── Métricas ──────────────────────────────────────────────────────────────
    private final Counter productosConsultadosCounter;
    private final Counter productoConsultadoPorIdCounter;
    private final Counter productoCreadoCounter;
    private final Timer getAllProductsTimer;

    public ProductService(ProductRepositoryPort repositoryPort,
                          EventPublisherPort eventPublisherPort,
                          MeterRegistry meterRegistry) {
        this.repositoryPort = repositoryPort;
        this.eventPublisherPort = eventPublisherPort;
        this.meterRegistry = meterRegistry;

        this.productosConsultadosCounter = Counter.builder("products.consulted")
                .description("Cantidad de veces que se listaron todos los productos")
                .register(meterRegistry);

        this.productoConsultadoPorIdCounter = Counter.builder("products.consulted.by.id")
                .description("Cantidad de consultas por ID")
                .register(meterRegistry);

        this.productoCreadoCounter = Counter.builder("products.created")
                .description("Cantidad de productos creados")
                .register(meterRegistry);

        this.getAllProductsTimer = Timer.builder("products.service.latency")
                .description("Latencia del caso de uso getAllProducts")
                .tag("operation", "getAllProducts")
                .register(meterRegistry);        
    }

    @Override
    public List<Product> getAllProducts() {
        productosConsultadosCounter.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        List<Product> result = repositoryPort.findAll();
        sample.stop(getAllProductsTimer);
        return result;
    }

    @Override
    public Optional<Product> getProductById(Long id) {
        productoConsultadoPorIdCounter.increment();        // ← métrica
        return repositoryPort.findById(id);
    }

    @Override
    public Product createProduct(Product product) {
        Product saved = repositoryPort.save(product);
        productoCreadoCounter.increment();                 // ← métrica
        eventPublisherPort.publishProductCreated(
                new ProductCreatedEvent(saved.getId(), saved.getName(),
                        saved.getQuantity(), saved.getPrice())
        );
        return saved;
    }
}