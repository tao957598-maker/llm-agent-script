;(function (window) {
  if (window.AiChat) return

  const EVENT_MAP = {
    AI_CHAT_SEND: 'onSend',
    AI_CHAT_ERROR: 'onError',
  }

  const AiChat = {
    iframe: null,
    options: {},

    init(options = {}) {
      this.options = options

      const {
        url,
        container = document.body,
        agentName,
        /** 业务注入 */
        docContent = '', // ⚠️ 字符串
        /** iframe 外观 */
        iframe: iframeOpt = {},
      } = options

      if (!url) {
        throw new Error('[AiChat] url is required')
      }

      // query 参数（尽量只放轻量的）
      const query = new URLSearchParams({
        agentName: agentName || ''
      }).toString()

      const iframe = document.createElement('iframe')
      iframe.src = `${url}?${query}`

      /** 固定悬浮，不影响第三方布局 */
      iframe.style.position = 'fixed'
      iframe.style.left = iframeOpt.left || '2%'
      iframe.style.right = iframeOpt.right || '20px'
      iframe.style.bottom = iframeOpt.bottom || '20px'
      iframe.style.width = iframeOpt.width || '360px'
      iframe.style.height = iframeOpt.height || '280px'
      iframe.style.border = 'none'
      iframe.style.zIndex = '999999'
      iframe.style.background = 'transparent'

      // if (iframeOpt.borderRadius) {
      //   iframe.style.borderRadius = iframeOpt.borderRadius
      //   iframe.style.overflow = 'hidden'
      // }
      if (iframeOpt.boxShadow) {
        iframe.style.boxShadow = iframeOpt.boxShadow
      }

      iframe.allow = 'clipboard-write'

      container.appendChild(iframe)
      this.iframe = iframe

      /** iframe 加载完成后注入配置 */
      iframe.onload = () => {
        iframe.contentWindow.postMessage(
          {
            type: 'AI_CHAT_INIT',
            payload: {
              docContent // ✅ 字符串
            }
          },
          '*'
        )

        if (typeof options.onReady === 'function') {
          options.onReady()
        }
      }

      /** 监听 iframe → 外部消息 */
      this._boundHandleMessage = this._handleMessage.bind(this)
      window.addEventListener('message', this._boundHandleMessage)
    },

    _handleMessage(event) {
      const { type, payload } = event.data || {}
      if (!type) return

      const cbName = EVENT_MAP[type]
      const cb = this.options?.[cbName]

      if (typeof cb === 'function') {
        cb(payload)
      }
    },

    /** 主动给 iframe 发消息（可选） */
    emit(type, payload) {
      if (!this.iframe || !this.iframe.contentWindow) return
      this.iframe.contentWindow.postMessage({ type, payload }, '*')
    },

    destroy() {
      if (this.iframe) {
        this.iframe.remove()
        this.iframe = null
      }
      window.removeEventListener('message', this._boundHandleMessage)
      this.options = {}
    }
  }

  window.AiChat = AiChat
})(window)
