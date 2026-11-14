// Picks and reduces a photo, and owns the preview URL's lifetime.
import { useCallback, useEffect, useState } from 'react';
import { getPhoto, putPhoto, type StoredPhoto } from '../../shared/storage/repository.ts';
import { newUuid } from '../../shared/lib/uuid.ts';
import { PHOTO_FAILURE_MESSAGES, PhotoError, processPhoto } from './process-photo.ts';

export type PhotoInfo = Pick<StoredPhoto, 'id' | 'width' | 'height' | 'bytes' | 'sha256'>;

export function usePhotoPreview(photoId: string | null) {
  const [preview, setPreview] = useState<{ url: string; info: PhotoInfo } | null>(null);
  useEffect(() => {
    if (!photoId) {
      setPreview(null);
      return;
    }
    let cancelled = false;
    let url: string | null = null;
    getPhoto(photoId).then((photo) => {
      if (cancelled || !photo) return;
      url = URL.createObjectURL(photo.blob);
      setPreview({ url, info: { id: photo.id, width: photo.width, height: photo.height, bytes: photo.bytes, sha256: photo.sha256 } });
    }).catch(() => setPreview(null));
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [photoId]);
  return preview;
}

export function usePhotoPicker(onPicked: (photoId: string) => void) {
  const [state, setState] = useState<{ kind: 'IDLE' | 'WORKING' } | { kind: 'FAILED'; message: string }>({ kind: 'IDLE' });

  const pick = useCallback(async (file: File | null) => {
    if (!file) return;
    setState({ kind: 'WORKING' });
    try {
      const processed = await processPhoto(file);
      const id = newUuid();
      await putPhoto({ id, blob: processed.blob, mime: processed.mime, sha256: processed.sha256, width: processed.width, height: processed.height, bytes: processed.bytes.length });
      onPicked(id);
      setState({ kind: 'IDLE' });
    } catch (cause) {
      const message = cause instanceof PhotoError ? PHOTO_FAILURE_MESSAGES[cause.failure] : '사진을 이 기기에 저장하지 못했습니다. 저장 공간을 확인하거나 사진 없이 계속하세요.';
      setState({ kind: 'FAILED', message });
    }
  }, [onPicked]);

  return { state, pick };
}
