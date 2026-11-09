export type PreferredProvider = 'AUTO' | 'PROVIDER_A' | 'PROVIDER_B'

export interface ProductRule {
  id: string
  sku: string
  name: string
  marginPercentage: number
  preferredProvider: PreferredProvider
  active: boolean
  updatedAt: string
}

export interface ProductRuleDraft {
  name: string
  marginPercentage: number
  preferredProvider: PreferredProvider
  active: boolean
}

export interface NewProductRule extends ProductRuleDraft {
  sku: string
}

export interface PriceResult {
  sku: string
  productName: string
  providerPrice: number
  marginPercentage: number
  finalPrice: number
  currency: string
  source: 'PROVIDER_A' | 'PROVIDER_B' | 'CACHE' | 'FALLBACK_CACHE'
  stale: boolean
  responseTimeMs: number
}

interface ApiErrorBody {
  message?: string
  fields?: Record<string, string>
}

export class ApiFailure extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
    this.name = 'ApiFailure'
  }
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  })

  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as ApiErrorBody
    const fieldErrors = Object.values(body.fields ?? {}).join(' ')
    throw new ApiFailure(fieldErrors || body.message || 'No fue posible completar la solicitud.', response.status)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const priceApi = {
  listRules: () => request<ProductRule[]>('/api/v1/product-rules'),
  createRule: (rule: NewProductRule) => request<ProductRule>('/api/v1/product-rules', {
    method: 'POST',
    body: JSON.stringify(rule),
  }),
  updateRule: (sku: string, rule: ProductRuleDraft) => request<ProductRule>(`/api/v1/product-rules/${encodeURIComponent(sku)}`, {
    method: 'PUT',
    body: JSON.stringify(rule),
  }),
  deleteRule: (sku: string) => request<void>(`/api/v1/product-rules/${encodeURIComponent(sku)}`, {
    method: 'DELETE',
  }),
  getPrice: (sku: string) => request<PriceResult>(`/api/v1/prices?sku=${encodeURIComponent(sku)}`),
}