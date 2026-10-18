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
    /*
     * 主动取消不是"网络异常"。
     *
     * 这里原来把所有错误一律包成 new Error(message)，把 name 弄丢了 ——
     * 结果用户点"停止"，界面上弹的是「网络异常，请稍后重试」。
     * 明明是他自己按的停止，却像是在报错。
     * 取消必须原样（带 name）抛出去，让调用方按 name 判断。
     */
    if (axios.isCancel(error) || error.code === 'ERR_CANCELED') {
      const cancelled = new Error('已取消')
      cancelled.name = 'CanceledError'
      return Promise.reject(cancelled)
    }
    const body = error.response?.data
    const message = body?.message || '网络异常，请稍后重试'
    return Promise.reject(new Error(message))
  }
)

export default http
