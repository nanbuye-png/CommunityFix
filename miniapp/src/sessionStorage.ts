// H5 sessions belong to a tab. Mini-program storage remains device-local.
export function readSessionValue(key: string): any {
  // #ifdef H5
  const raw = window.sessionStorage.getItem('cf_tab_v1_' + key)
  try { return raw ? JSON.parse(raw) : null } catch { return null }
  // #endif
  // #ifndef H5
  return uni.getStorageSync('cf_' + key)
  // #endif
}
export function writeSessionValue(key: string, value: any) {
  // #ifdef H5
  window.sessionStorage.setItem('cf_tab_v1_' + key, JSON.stringify(value))
  return
  // #endif
  // #ifndef H5
  uni.setStorageSync('cf_' + key, value)
  // #endif
}
export function clearSessionValues() {
  for (const key of ['token', 'session']) {
    // #ifdef H5
    window.sessionStorage.removeItem('cf_tab_v1_' + key)
    // #endif
    // #ifndef H5
    uni.removeStorageSync('cf_' + key)
    // #endif
  }
}
export function expireSession(token: string) {
  // A late failure from a previous account must not clear a newer login.
  if (token && token === readSessionValue('token')) {
    clearSessionValues()
    uni.reLaunch({ url: '/pages/login/index' })
  }
}
