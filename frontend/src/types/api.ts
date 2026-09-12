export interface ApiResult<T = unknown> {
  code: number;
  message: string;
  data: T;
  traceId: string;
}

export class ApiError extends Error {
  code: number;
  traceId: string;
  httpStatus?: number;

  constructor(
    code: number,
    message: string,
    traceId: string,
    httpStatus?: number
  ) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
    this.httpStatus = httpStatus;
  }
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  email?: string;
  nickname?: string;
}

export interface LoginResponse {
  accessToken: string;
  userId: number;
  username: string;
}

export interface Workspace {
  id: number;
  name: string;
  description: string;
  ownerId: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateWorkspaceRequest {
  name: string;
  description?: string;
}