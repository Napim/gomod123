GomodOfficialLoadoutCreate=(g,i,r2,hC,Z,PlayerModel,SkinEnum,DisplayEnum,RenderContextEnum)=>{
const w=window,
EMPTY_STATE={enabled:!1,available:!1,source:"unavailable",liveSocket:!1,outfitName:"",cosmetics:[],badgeId:0,logoColor:"",plusColor:"",logoAlwaysShow:!1,updatedAt:0},
getListeners=()=>w.__gomodOfficialLoadoutListeners||(w.__gomodOfficialLoadoutListeners=new Set),
notify=()=>{getListeners().forEach(fn=>{try{fn()}catch{}})},
getSnapshot=()=>({
  open:w.__gomodOfficialLoadoutOpen===!0,
  mounted:(w.__gomodOfficialLoadoutMountCount||0)>0,
  refreshing:w.__gomodOfficialLoadoutRefreshing===!0,
  data:w.__gomodOfficialLoadoutData||EMPTY_STATE
}),
publishData=raw=>{
  let encoded="unavailable",data=EMPTY_STATE;
  try{
    if(typeof raw==="string"){
      const parsed=JSON.parse(raw);
      if(parsed&&typeof parsed==="object"&&typeof parsed.enabled==="boolean"){
        encoded=raw;
        data={
          enabled:parsed.enabled===!0,
          available:parsed.available===!0,
          source:typeof parsed.source==="string"?parsed.source:"unavailable",
          liveSocket:parsed.liveSocket===!0,
          outfitName:typeof parsed.outfitName==="string"?parsed.outfitName:"",
          cosmetics:Array.isArray(parsed.cosmetics)?parsed.cosmetics:[],
          badgeId:typeof parsed.badgeId==="number"?parsed.badgeId:0,
          logoColor:typeof parsed.logoColor==="string"?parsed.logoColor:"",
          plusColor:typeof parsed.plusColor==="string"?parsed.plusColor:"",
          logoAlwaysShow:parsed.logoAlwaysShow===!0,
          updatedAt:typeof parsed.updatedAt==="number"?parsed.updatedAt:0
        };
      }
    }
  }catch{encoded="unavailable";data=EMPTY_STATE}
  const changed=w.__gomodOfficialLoadoutRaw!==encoded;
  w.__gomodOfficialLoadoutRaw=encoded;
  w.__gomodOfficialLoadoutData=data;
  if(!data.enabled&&w.__gomodOfficialLoadoutOpen){w.__gomodOfficialLoadoutOpen=!1;notify();return data}
  if(changed)notify();
  return data;
},
fetchOfficialLoadout=()=>{
  if(w.__gomodOfficialLoadoutReq)return w.__gomodOfficialLoadoutReq;
  const bridge=w.lunar;
  if(!bridge||typeof bridge.gomodGetOfficialLoadout!=="function"){publishData(null);return Promise.resolve(EMPTY_STATE)}
  w.__gomodOfficialLoadoutReq=Promise.resolve()
    .then(()=>bridge.gomodGetOfficialLoadout())
    .then(raw=>publishData(raw),()=>EMPTY_STATE)
    .finally(()=>{w.__gomodOfficialLoadoutReq=null});
  return w.__gomodOfficialLoadoutReq;
},
triggerRefresh=()=>{
  const bridge=w.lunar;
  if(bridge&&typeof bridge.gomodRefreshOfficialLoadout==="function"){
    w.__gomodOfficialLoadoutRefreshing=!0;notify();
    Promise.resolve().then(()=>bridge.gomodRefreshOfficialLoadout()).catch(()=>!1).finally(()=>{
      w.setTimeout(()=>{fetchOfficialLoadout().finally(()=>{w.__gomodOfficialLoadoutRefreshing=!1;notify()})},650);
    });
  }else{
    fetchOfficialLoadout();
  }
},
setOpen=next=>{
  const value=!!next;
  if(w.__gomodOfficialLoadoutOpen===value)return;
  w.__gomodOfficialLoadoutOpen=value;
  notify();
  if(value)triggerRefresh();
},
ensurePolling=()=>{
  if(w.__gomodOfficialLoadoutTimer==null){
    fetchOfficialLoadout();
    w.__gomodOfficialLoadoutTimer=w.setInterval(()=>{fetchOfficialLoadout()},2000);
  }
},
maybeStopPolling=()=>{
  const l=w.__gomodOfficialLoadoutListeners;
  if(l&&l.size>0)return;
  if(w.__gomodOfficialLoadoutTimer!=null){w.clearInterval(w.__gomodOfficialLoadoutTimer);w.__gomodOfficialLoadoutTimer=null}
},
subscribe=fn=>{
  const l=getListeners();
  l.add(fn);
  ensurePolling();
  return()=>{l.delete(fn);maybeStopPolling()};
},
useOfficialLoadout=()=>{
  const[,bump]=g.useState(0);
  g.useEffect(()=>subscribe(()=>bump(v=>v+1)),[]);
  return getSnapshot();
},
MoonIcon=({size=15})=>i.jsx("svg",{width:size,height:size,viewBox:"0 0 20 20",fill:"none",xmlns:"http://www.w3.org/2000/svg",children:i.jsx("path",{d:"M16.4 12.7A7 7 0 0 1 7.3 3.6a7 7 0 1 0 9.1 9.1Z",stroke:"currentColor",strokeWidth:"1.8",strokeLinecap:"round",strokeLinejoin:"round"})}),
SplitIcon=()=>i.jsx("svg",{width:"17",height:"17",viewBox:"0 0 20 20",fill:"none",xmlns:"http://www.w3.org/2000/svg",children:[i.jsx("rect",{x:"2.5",y:"3.5",width:"15",height:"13",rx:"2.5",stroke:"currentColor",strokeWidth:"1.7"},"r"),i.jsx("path",{d:"M11.5 3.5V16.5",stroke:"currentColor",strokeWidth:"1.7"},"d"),i.jsx("path",{d:"M14.2 8.2L15.8 10L14.2 11.8",stroke:"currentColor",strokeWidth:"1.6",strokeLinecap:"round",strokeLinejoin:"round"},"a")]}),
RefreshIcon=({spinning})=>i.jsx("svg",{width:"15",height:"15",viewBox:"0 0 20 20",fill:"none",style:{transition:"transform 0.4s",transform:spinning?"rotate(180deg)":"none"},children:i.jsx("path",{d:"M16.5 10a6.5 6.5 0 1 1-1.9-4.6M16.5 3.5v3.2h-3.2",stroke:"currentColor",strokeWidth:"1.8",strokeLinecap:"round",strokeLinejoin:"round"})}),
CloseIcon=()=>i.jsx("svg",{width:"16",height:"16",viewBox:"0 0 24 24",fill:"none",children:i.jsx("path",{d:"M6 18L12 12M12 12L18 6M12 12L6 6M12 12L18 18",stroke:"currentColor",strokeWidth:"2",strokeLinecap:"round",strokeLinejoin:"round"})}),
GomodOfficialLoadoutToggle=()=>{
  const snap=useOfficialLoadout();
  if(!snap.data.enabled)return null;
  const open=snap.open;
  return i.jsx(r2,{
    placement:"left",
    content:i.jsx(hC,{children:i.jsx(Z,{fontFamily:"modern",fontSize:"12px",color:"var(--colors-neutrals-space-11)",fontWeight:"500",children:open?"Hide Official Lunar Loadout":"Official Lunar Loadout (how non-gomod users see you)"})}),
    children:i.jsx("div",{
      role:"button",
      "data-gomod-official-loadout-toggle":"v1-chunked",
      "aria-label":open?"Hide Official Lunar Loadout":"Show Official Lunar Loadout",
      onMouseDown:e=>{e.stopPropagation();setOpen(!open)},
      style:{
        display:"flex",alignItems:"center",justifyContent:"center",
        padding:"4px 6px",borderRadius:"6px",cursor:"pointer",
        color:open?"#8ec0ff":"var(--colors-neutrals-space-11)",
        background:open?"rgba(75,137,255,.22)":"transparent",
        border:open?"1px solid rgba(127,178,255,.45)":"1px solid transparent",
        transition:"all 0.15s"
      },
      children:i.jsx(SplitIcon,{})
    })
  });
},
GomodYourLoadoutTitleBar=({TitleBar,icon,onHelp})=>{
  g.useEffect(()=>{
    w.__gomodOfficialLoadoutMountCount=(w.__gomodOfficialLoadoutMountCount||0)+1;
    notify();
    return()=>{
      w.__gomodOfficialLoadoutMountCount=Math.max(0,(w.__gomodOfficialLoadoutMountCount||1)-1);
      notify();
    };
  },[]);
  return i.jsx(TitleBar,{
    canResize:!0,
    name:"Your Loadout",
    icon,
    onHelp,
    actions:i.jsx(GomodOfficialLoadoutToggle,{})
  });
},
GomodOfficialLoadoutPanel=()=>{
  const snap=useOfficialLoadout(),
  [listOpen,setListOpen]=g.useState(!1),
  data=snap.data,
  cosmeticsKey=g.useMemo(()=>JSON.stringify((data.cosmetics||[]).map(c=>c&&c.metadata?{id:c.id,metadata:c.metadata}:{id:c.id})),[data.cosmetics]),
  modelCosmetics=g.useMemo(()=>{try{return JSON.parse(cosmeticsKey)}catch{return[]}},[cosmeticsKey]);
  if(!snap.open||!snap.mounted||!data.enabled)return null;
  const count=(data.cosmetics||[]).length;
  return i.jsxs("div",{
    "data-gomod-official-loadout":"v2-chunked",
    style:{
      display:"flex",flexDirection:"column",alignSelf:"stretch",height:"100%",
      flex:"0 2 290px",width:"290px",minWidth:"230px",maxWidth:"320px",
      gap:"var(--spacings-radii-3)"
    },
    children:[
      i.jsxs("div",{
        style:{display:"flex",justifyContent:"space-between",alignItems:"center",width:"100%",minHeight:"30px"},
        children:[
          i.jsxs("div",{
            style:{display:"flex",alignItems:"center",gap:"8px",minWidth:0,flex:1},
            children:[
              i.jsx("div",{style:{display:"flex",color:"#8ec0ff",flexShrink:0},children:i.jsx(MoonIcon,{size:18})}),
              i.jsx(Z,{
                color:"var(--colors-neutrals-space-11)",fontFamily:"modern",fontSize:"18px",
                fontWeight:"500",lineHeight:"28px",
                CSS:{overflow:"hidden",whiteSpace:"nowrap",textOverflow:"ellipsis"},
                children:"Official Loadout"
              })
            ]
          }),
          i.jsxs("div",{
            style:{display:"flex",alignItems:"center",gap:"6px",flexShrink:0},
            children:[
              data.liveSocket&&i.jsx(r2,{
                placement:"bottom",
                content:i.jsx(hC,{children:i.jsx(Z,{fontFamily:"modern",fontSize:"12px",color:"var(--colors-neutrals-space-11)",fontWeight:"500",children:"Refresh from official Lunar"})}),
                children:i.jsx("div",{
                  role:"button",
                  onMouseDown:e=>{e.stopPropagation();triggerRefresh()},
                  style:{display:"flex",padding:"4px",cursor:"pointer",color:"var(--colors-neutrals-space-11)",borderRadius:"5px"},
                  children:i.jsx(RefreshIcon,{spinning:snap.refreshing})
                })
              }),
              i.jsx(r2,{
                placement:"bottom",
                content:i.jsx(hC,{children:i.jsx(Z,{fontFamily:"modern",fontSize:"12px",color:"var(--colors-neutrals-space-11)",fontWeight:"500",children:"Close Official Loadout"})}),
                children:i.jsx("div",{
                  role:"button",
                  onMouseDown:e=>{e.stopPropagation();setOpen(!1)},
                  style:{display:"flex",padding:"4px",cursor:"pointer",color:"var(--colors-neutrals-space-11)",borderRadius:"5px"},
                  children:i.jsx(CloseIcon,{})
                })
              })
            ]
          })
        ]
      }),
      i.jsxs("div",{
        style:{
          display:"flex",flexDirection:"column",flex:"1 1 0",minHeight:0,
          borderRadius:"var(--spacings-radii-2)",
          background:"var(--colors-neutrals-space-2)",
          border:"1px solid rgba(127,178,255,.18)",
          overflow:"hidden",position:"relative"
        },
        children:[
          i.jsxs("div",{
            style:{
              display:"flex",flexDirection:"column",gap:"2px",
              padding:"10px 12px 6px",
              borderBottom:"1px solid rgba(255,255,255,.06)",
              flexShrink:0
            },
            children:[
              i.jsxs("div",{
                style:{display:"flex",alignItems:"center",justifyContent:"space-between",gap:"6px"},
                children:[
                  i.jsx(Z,{
                    fontFamily:"modern",fontSize:"13px",fontWeight:"600",
                    color:"var(--colors-neutrals-space-12)",
                    CSS:{overflow:"hidden",whiteSpace:"nowrap",textOverflow:"ellipsis"},
                    children:data.outfitName||"Official Lunar Fit"
                  }),
                  i.jsx("span",{
                    style:{
                      fontSize:"10px",fontWeight:700,letterSpacing:"0.04em",
                      padding:"2px 6px",borderRadius:"4px",flexShrink:0,
                      background:data.source==="live-push"?"rgba(46,204,113,.18)":"rgba(127,178,255,.18)",
                      color:data.source==="live-push"?"#6fe7a2":"#9bc8ff",
                      border:data.source==="live-push"?"1px solid rgba(46,204,113,.35)":"1px solid rgba(127,178,255,.35)"
                    },
                    children:data.source==="live-push"?"LIVE":"OFFICIAL"
                  })
                ]
              }),
              i.jsx(Z,{
                fontFamily:"modern",fontSize:"11px",fontWeight:"400",
                color:"var(--colors-neutrals-space-9)",
                children:"How non-gomod Lunar users see you"
              })
            ]
          }),
          i.jsx("div",{
            style:{flex:"1 1 0",minHeight:0,position:"relative",width:"100%"},
            children:data.available
              ?i.jsx(PlayerModel,{
                id:"GOMOD_OFFICIAL_LOADOUT_DUMMY",
                skin:SkinEnum.SELF,
                display:DisplayEnum.DUMMY,
                cosmetics:modelCosmetics,
                renderContext:RenderContextEnum.IN_COSMETIC_PLAYER_MODEL,
                zoom:.65,
                canDragModel:!0,
                canRotateHorizontal:!0,
                canRotateVertical:!0,
                canManuallyZoom:!0
              },"GOMOD_OFFICIAL_LOADOUT_DUMMY")
              :i.jsxs("div",{
                style:{
                  display:"flex",flexDirection:"column",alignItems:"center",justifyContent:"center",
                  height:"100%",padding:"20px",gap:"10px",textAlign:"center"
                },
                children:[
                  i.jsx(Z,{
                    fontFamily:"modern",fontSize:"12px",fontWeight:"500",
                    color:"var(--colors-neutrals-space-10)",
                    children:"Official Lunar profile not loaded yet."
                  }),
                  data.liveSocket&&i.jsx("button",{
                    type:"button",
                    onMouseDown:e=>{e.stopPropagation();triggerRefresh()},
                    style:{
                      padding:"6px 12px",borderRadius:"6px",cursor:"pointer",
                      background:"rgba(75,137,255,.22)",color:"#cfe3ff",
                      border:"1px solid rgba(127,178,255,.45)",fontSize:"12px",fontWeight:600
                    },
                    children:"Load Official Profile"
                  })
                ]
              })
          }),
          listOpen&&count>0&&i.jsx("div",{
            style:{
              maxHeight:"125px",overflowY:"auto",
              padding:"6px 10px",
              borderTop:"1px solid rgba(255,255,255,.06)",
              background:"rgba(0,0,0,.22)",
              display:"flex",flexDirection:"column",gap:"4px",flexShrink:0
            },
            children:(data.cosmetics||[]).map((c,idx)=>i.jsxs("div",{
              style:{
                display:"flex",alignItems:"center",justifyContent:"space-between",
                fontSize:"11px",color:"var(--colors-neutrals-space-11)",
                padding:"2px 4px",borderRadius:"4px",background:"rgba(255,255,255,.03)"
              },
              children:[
                i.jsx("span",{style:{overflow:"hidden",whiteSpace:"nowrap",textOverflow:"ellipsis"},children:c.name||("Cosmetic #"+c.id)}),
                i.jsx("span",{style:{color:"var(--colors-neutrals-space-8)",fontSize:"10px",marginLeft:"6px",flexShrink:0},children:"#"+c.id})
              ]
            },c.id+"_"+idx))
          }),
          i.jsxs("div",{
            style:{
              display:"flex",flexDirection:"column",gap:"6px",
              padding:"8px 10px",
              borderTop:"1px solid rgba(255,255,255,.06)",
              background:"rgba(0,0,0,.16)",flexShrink:0
            },
            children:[
              i.jsxs("div",{
                onMouseDown:e=>{e.stopPropagation();if(count>0)setListOpen(v=>!v)},
                style:{
                  display:"flex",alignItems:"center",justifyContent:"space-between",
                  cursor:count>0?"pointer":"default",
                  fontSize:"11px",fontWeight:600,color:"var(--colors-neutrals-space-11)"
                },
                children:[
                  i.jsx("span",{children:count===0?"No official cosmetics equipped":count+(count===1?" official cosmetic":" official cosmetics")}),
                  count>0&&i.jsx("span",{style:{color:"var(--colors-neutrals-space-9)"},children:listOpen?"▴":"▾"})
                ]
              }),
              data.badgeId>0&&i.jsx("div",{
                style:{display:"flex",flexWrap:"wrap",gap:"5px",alignItems:"center"},
                children:i.jsx("span",{
                  style:{fontSize:"10px",padding:"2px 6px",borderRadius:"4px",background:"rgba(255,255,255,.06)",color:"var(--colors-neutrals-space-11)"},
                  children:"Badge #"+data.badgeId
                })
              })
            ]
          })
        ]
      })
    ]
  });
},
GomodLockerRow=({base:Base,children,...rest})=>i.jsxs(Base,{...rest,children:[children,i.jsx(GomodOfficialLoadoutPanel,{})]});
return{GomodYourLoadoutTitleBar,GomodOfficialLoadoutToggle,GomodOfficialLoadoutPanel,GomodLockerRow};
}
