const HOME = '/pages/home/index'
const history: string[] = []
let returningTo: string | null = null

export function resetTabHistory() {
  history.length = 0
  returningTo = null
}

export function noteTabShown(path: string) {
  if (returningTo === path) {
    returningTo = null
    return
  }
  returningTo = null
  if (history[history.length - 1] !== path) history.push(path)
  if (history.length > 20) history.shift()
}

export function backToPreviousTab(current: string) {
  if (history[history.length - 1] !== current) noteTabShown(current)
  history.pop()
  const destination = history[history.length - 1] || HOME
  if (!history.length) history.push(destination)
  returningTo = destination
  uni.switchTab({
    url: destination,
    fail() {
      returningTo = null
      if (history[history.length - 1] !== current) history.push(current)
    }
  })
}
