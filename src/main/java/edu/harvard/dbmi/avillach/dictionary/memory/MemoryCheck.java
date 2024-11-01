package edu.harvard.dbmi.avillach.dictionary.memory;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

@Service
public class MemoryCheck {

    @PostConstruct
    public void checkMemory() {
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        System.out.println("Max Heap Memory (Xmx): " + maxMemory + " MB");
    }

}
