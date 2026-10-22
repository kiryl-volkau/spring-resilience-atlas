package com.acme.care.dailycare.media;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class MediaUploadSpool {

    private final MediaStore mediaStore;
    private final Queue<PendingUpload> pending = new ConcurrentLinkedQueue<>();

    public MediaUploadSpool(MediaStore mediaStore) {
        this.mediaStore = mediaStore;
    }

    public void enqueue(PendingUpload upload) {
        pending.add(upload);
    }

    @PreDestroy
    void flushPending() {
        PendingUpload upload;
        while ((upload = pending.poll()) != null) {
            mediaStore.store(upload);
        }
    }
}
