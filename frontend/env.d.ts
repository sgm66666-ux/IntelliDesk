/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<object, object, any>;
  export default component;
}

interface Window {
  __INJECT_ACCESS_TOKEN__?: () => string | null;
  __SET_ACCESS_TOKEN__?: (token: string) => void;
  __CLEAR_AUTH__?: () => void;
}