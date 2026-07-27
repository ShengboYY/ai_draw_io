const SERVER_ERROR_CODES: Record<string, { zh: string; en: string }> = {
  TURN_V2_BRIDGE_TIMEOUT: {
    zh: '请求处理超时，请重试。',
    en: 'The request timed out. Please try again.',
  },
  TURN_V2_BRIDGE_FAILED: {
    zh: '请求处理失败，请重试。',
    en: 'The request could not be processed. Please try again.',
  },
  TURN_EXECUTION_FAILED: {
    zh: '后端执行过程中发生错误，请检查服务日志后重试。',
    en: 'The backend failed while executing the request. Check the service logs and try again.',
  },
  CANVAS_CONTEXT_CONFLICT: {
    zh: '生成期间画布内容发生了变化，请重新发送请求。',
    en: 'The canvas changed while the diagram was being generated. Please send the request again.',
  },
  CANVAS_VERSION_CONFLICT: {
    zh: '画布版本已经更新，请刷新后重新发送请求。',
    en: 'The canvas version has changed. Refresh and send the request again.',
  },
  CLARIFICATION_DEFERRED: {
    zh: '还需要确认你希望如何使用附件或修改画布，请补充说明。',
    en: 'More detail is needed about how to use the attachment or update the canvas.',
  },
  UNSUPPORTED_DIRECT_EDIT: {
    zh: '暂不支持用附件直接覆盖已有画布，请在空白画布中重建或说明要保留的内容。',
    en: 'Direct attachment reconstruction cannot replace an existing canvas without clarification.',
  },
};

/** Converts internal transport details into one bounded, user-facing message. */
export const streamErrorMessage = (raw: unknown, useChinese: boolean): string => {
  const text = raw instanceof Error ? raw.message : String(raw ?? '');
  const knownCode = Object.keys(SERVER_ERROR_CODES).find(code => text.includes(code));
  if (knownCode) {
    return useChinese ? SERVER_ERROR_CODES[knownCode].zh : SERVER_ERROR_CODES[knownCode].en;
  }
  if (/HTTP_(?:5\d\d)|status:\s*5\d\d/i.test(text)) {
    return useChinese ? '服务器暂时无法处理请求，请重试。' : 'The server could not process the request. Please try again.';
  }
  if (/HTTP_(?:401|403)|status:\s*(?:401|403)/i.test(text)) {
    return useChinese ? '登录状态已失效，请重新登录。' : 'Your session has expired. Please sign in again.';
  }
  if (/Failed to fetch|NetworkError|Load failed/i.test(text)) {
    return useChinese ? '网络连接失败，请检查连接后重试。' : 'The network connection failed. Please try again.';
  }
  return useChinese ? '请求失败，请重试。' : 'The request failed. Please try again.';
};
