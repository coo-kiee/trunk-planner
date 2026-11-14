// Thin promise layer over IndexedDB with typed failures. It never deletes a database to recover from anything.
export const DB_NAME = 'cargo-phone';
export const DB_VERSION = 2;
export const STORES = { pairing: 'pairing', editor: 'editor', photos: 'photos', outbox: 'outbox', confirmed: 'confirmed', quarantine: 'quarantine' } as const;

export type StorageFailure = 'UNAVAILABLE' | 'FUTURE_VERSION' | 'QUOTA' | 'WRITE_FAILED' | 'BLOCKED';

export class StorageError extends Error {
  readonly failure: StorageFailure;
  constructor(failure: StorageFailure, cause?: unknown) {
    super(failure, { cause });
    this.failure = failure;
  }
}

function toStorageError(cause: unknown): StorageError {
  if (cause instanceof StorageError) return cause;
  const name = (cause as { name?: string } | null)?.name;
  if (name === 'QuotaExceededError') return new StorageError('QUOTA', cause);
  if (name === 'VersionError') return new StorageError('FUTURE_VERSION', cause);
  return new StorageError('WRITE_FAILED', cause);
}

let opening: Promise<IDBDatabase> | null = null;

export function openDatabase(): Promise<IDBDatabase> {
  if (opening) return opening;
  opening = new Promise<IDBDatabase>((resolve, reject) => {
    let request: IDBOpenDBRequest;
    try {
      request = indexedDB.open(DB_NAME, DB_VERSION);
    } catch (cause) {
      reject(new StorageError('UNAVAILABLE', cause));
      return;
    }
    request.onupgradeneeded = () => {
      const db = request.result;
      const create = (name: string, options?: IDBObjectStoreParameters) => {
        if (!db.objectStoreNames.contains(name)) db.createObjectStore(name, options);
      };
      create(STORES.pairing);
      create(STORES.editor);
      create(STORES.photos);
      create(STORES.outbox, { keyPath: 'key' });
      create(STORES.confirmed, { keyPath: 'acceptedItemId' });
      create(STORES.quarantine); // v2: unreadable single records move here instead of being overwritten.
    };
    request.onblocked = () => reject(new StorageError('BLOCKED'));
    request.onerror = () => reject(request.error?.name === 'VersionError' ? new StorageError('FUTURE_VERSION', request.error) : new StorageError('UNAVAILABLE', request.error));
    request.onsuccess = () => {
      const db = request.result;
      db.onversionchange = () => { db.close(); opening = null; };
      resolve(db);
    };
  });
  opening.catch(() => { opening = null; });
  return opening;
}

export const asPromise = <T>(request: IDBRequest<T>): Promise<T> =>
  new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });

type StoreName = (typeof STORES)[keyof typeof STORES];

/** Runs `work` in one transaction; its value is returned only after the transaction has actually committed. */
export async function transact<T>(stores: StoreName[], mode: IDBTransactionMode, work: (tx: IDBTransaction) => Promise<T> | T): Promise<T> {
  const db = await openDatabase();
  try {
    const tx = db.transaction(stores, mode);
    const committed = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onabort = () => reject(tx.error ?? new Error('aborted'));
      tx.onerror = () => reject(tx.error);
    });
    let value: T;
    try {
      value = await work(tx);
    } catch (cause) {
      try { tx.abort(); } catch { /* already finished */ }
      await committed.catch(() => undefined);
      throw cause;
    }
    await committed;
    return value;
  } catch (cause) {
    throw toStorageError(cause);
  }
}
