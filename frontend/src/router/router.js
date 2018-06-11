/* eslint-disable */
import Vue from 'vue'
import Router from 'vue-router'
import Search from '@/components/Search'
import ListView from '@/components/ListView'
import UserAccount from '@/components/UserAccount'

Vue.use(Router);

const routes = [
  { path: '/', component: Search },
  { path: '/offers', component: ListView, props: (route) => ({ search: route.query.search }) },
  { path: '/account', component: UserAccount}
];

export default new Router({
  routes: routes
})
