import axios from 'axios'

/**
 * 统一 HTTP 客户端。
 * 后端返回 ApiResponse { code, message, data }：
 *   code === 0 时直接返回 data，否则抛出携带 message 的错误。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body.data
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    const body = error.response?.data
    const message = body?.message || '网络异常，请稍后重试'
    return Promise.reject(new Error(message))
  }
)

export default http
