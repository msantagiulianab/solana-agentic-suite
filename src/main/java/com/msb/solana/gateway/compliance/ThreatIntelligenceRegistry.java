package com.msb.solana.gateway.compliance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.serialization.Base58;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory threat intelligence dataset of known high-risk Solana addresses.
 *
 * <p>The dataset is an embedded classpath resource
 * ({@code compliance/threat-intelligence.json}) loaded once at startup and
 * indexed by address for O(1) lookups on the hot screening path. Loading fails
 * closed: a missing resource, malformed JSON, or a non-32-byte address aborts
 * application startup rather than silently serving an incomplete blocklist.
 */
@Component
public class ThreatIntelligenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ThreatIntelligenceRegistry.class);

    private static final String DATASET_RESOURCE = "compliance/threat-intelligence.json";

    private final Map<String, ThreatIntelligenceEntry> entriesByAddress;

    public ThreatIntelligenceRegistry(ObjectMapper objectMapper) {
        this.entriesByAddress = loadDataset(objectMapper);
    }

    /**
     * @return the matching threat intelligence entry, or empty when the address
     *         is not present in the dataset.
     */
    public Optional<ThreatIntelligenceEntry> lookup(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entriesByAddress.get(address));
    }

    /**
     * @return an immutable snapshot of every entry in the dataset.
     */
    public List<ThreatIntelligenceEntry> getAllEntries() {
        return List.copyOf(entriesByAddress.values());
    }

    private static Map<String, ThreatIntelligenceEntry> loadDataset(ObjectMapper objectMapper) {
        try (InputStream in = ThreatIntelligenceRegistry.class.getClassLoader()
                .getResourceAsStream(DATASET_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Threat intelligence dataset not found on classpath: " + DATASET_RESOURCE);
            }

            List<ThreatIntelligenceEntry> entries = objectMapper.readValue(
                    in, new TypeReference<List<ThreatIntelligenceEntry>>() {});

            Map<String, ThreatIntelligenceEntry> index = new HashMap<>();
            for (ThreatIntelligenceEntry entry : entries) {
                if (entry == null || entry.address() == null || entry.address().isBlank()) {
                    throw new IllegalStateException(
                            "Threat intelligence dataset contains a blank entry");
                }
                if (Base58.decode(entry.address()).length != 32) {
                    throw new IllegalStateException(
                            "Threat intelligence entry is not a valid 32-byte Solana address: "
                                    + entry.address());
                }
                index.put(entry.address(), entry);
            }
            log.info("Threat intelligence dataset loaded: {} addresses indexed", index.size());
            return Collections.unmodifiableMap(index);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load threat intelligence dataset: " + DATASET_RESOURCE, e);
        }
    }
}
