GomodLunarOnlineLogo=e=>{
const[online,setOnline]=g.useState(!1);g.useEffect(()=>{let alive=!0,pending=!1;const refresh=async()=>{if(pending)return;pending=!0;try{const bridge=window.lunar,value=bridge&&typeof bridge.gomodIsLunarOnlineConnected==="function"?await bridge.gomodIsLunarOnlineConnected():!1;if(alive)setOnline(value===!0||value==="true"||value===1||value==="1")}catch{if(alive)setOnline(!1)}finally{pending=!1}};refresh();const timer=window.setInterval(refresh,500);return()=>{alive=!1;window.clearInterval(timer)}},[]);return i.jsx(wa,{...e,gomodOffline:!online});
}
