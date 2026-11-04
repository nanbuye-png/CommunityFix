<script setup lang="ts">
import { notificationConnection } from '../../liveNotifications'
import { ref } from 'vue'
import { onShow,onHide } from '@dcloudio/uni-app'
import { request,date } from '../../api'
import { useSession } from '../../stores/session'
import { backToPreviousTab,noteTabShown } from '../../tabNavigation'
const rows=ref<any[]>([]),page=ref(1),total=ref(0),error=ref(false)
async function load(reset=true){if(!useSession().requireLogin())return;if(reset)page.value=1;error.value=false;try{const result=await request('/notifications','GET',{page:page.value,size:20});rows.value=reset?result.records:[...rows.value,...result.records];total.value=result.total}catch{error.value=true}}
const liveRefresh=()=>load()
onShow(()=>{noteTabShown('/pages/messages/index');load();uni.$off('cf-notifications',liveRefresh);uni.$on('cf-notifications',liveRefresh)})
onHide(()=>uni.$off('cf-notifications',liveRefresh))
async function read(row:any){await request(`/notifications/${row.id}/read`,'POST');row.readStatus=1;if(row.supportSessionId)uni.navigateTo({url:`/pages/support/index?id=${row.supportSessionId}`});else if(row.orderId)uni.navigateTo({url:`/pages/detail/index?id=${row.orderId}&billing=1`})}
async function more(){page.value++;await load(false)}
</script>
<template><view class="page"><view class="back-link" @click="backToPreviousTab('/pages/messages/index')">‹ 返回上一步</view><view class="eyebrow">服务消息 · 及时知晓</view><view class="page-title">消息通知</view><view class="small">在线提醒：{{notificationConnection}}</view><button class="secondary" @click="load()">刷新消息</button><button class="secondary" @click="uni.navigateTo({url:'/pages/support/index'})">在线客服</button><view v-if="error" class="error" @click="load()">加载失败，点击重试</view><view v-if="!rows.length&&!error" class="empty">暂无新消息<br>工单进度更新后，会在这里通知你</view><view v-for="message in rows" :key="message.id" class="card" @click="read(message)"><view class="row"><text class="order-title">{{message.title}}</text><text v-if="!Number(message.readStatus)" class="pill">未读</text></view><view class="muted">{{message.content}}</view><button v-if="message.orderId" class="secondary" @click.stop="read(message)">{{String(message.eventKey||'').startsWith('quote:')?'查看并确认报价':'查看工单'}}</button><view class="small spaced">{{date(message.createdAt)}}</view></view><button v-if="rows.length<total" class="secondary" @click="more">加载更多</button></view></template>
