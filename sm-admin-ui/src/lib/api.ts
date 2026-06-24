import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
})

export const jobApi = {
  getAll: () => api.get('/jobs'),
  getById: (id: string) => api.get(`/jobs/${id}`),
  start: (id: string) => api.post(`/jobs/${id}/start`),
  stop: (id: string) => api.post(`/jobs/${id}/stop`),
  pause: (id: string) => api.post(`/jobs/${id}/pause`),
}

export const connectorApi = {
  getAll: (type: string) => api.get(`/connectors/${type}`),
  create: (data: any) => api.post('/connectors', data),
  delete: (id: string) => api.delete(`/connectors/${id}`),
}

export const statusApi = {
  getSystemStatus: () => api.get('/system/status'),
  getThroughput: () => api.get('/system/throughput'),
  getLogs: () => api.get('/system/logs'),
}

export default api
