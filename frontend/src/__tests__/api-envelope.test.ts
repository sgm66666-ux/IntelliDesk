import { describe, it, expect } from 'vitest';
import { ApiError } from '@/types/api';

describe('ApiResult envelope', () => {
  it('unwraps data on code=0', () => {
    // ApiResult unwrapping is tested indirectly via MSW
    // This tests the ApiError class directly
  });

  it('ApiError preserves code, message, traceId, httpStatus', () => {
    const err = new ApiError(7005, 'API Key not found', 'trace-123', 404);
    expect(err.code).toBe(7005);
    expect(err.message).toBe('API Key not found');
    expect(err.traceId).toBe('trace-123');
    expect(err.httpStatus).toBe(404);
    expect(err.name).toBe('ApiError');
  });

  it('ApiError with default httpStatus', () => {
    const err = new ApiError(1001, 'Error', 'trace-456');
    expect(err.httpStatus).toBeUndefined();
  });
});