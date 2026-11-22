package me.napim.gomod.features;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LunarLockerUiBundlePatcherTest {

    @Test
    public void patchesSyntheticChunkedLockerFilesIdempotentlyWithDifferentMinifiedNames() throws Exception {
        String fragment = readChunkedFragment();
        String extrasFragment = readChunkedExtrasFragment();

        String slider = "K1=R1.forwardRef((p,q)=>{const a=h.useRef(null),o=h.useRef(null),"
                + "fmt=h.useRef(v=>v.toFixed(2)),drag=h.useRef(!1),toStr=n=>n.toFixed(2);"
                + "return x.jsx(\"div\",{})});";
        String patchedSlider = LunarLockerUiBundlePatcher.transformChunkedSliderFormatting(slider);
        assertTrue(patchedSlider.contains("h.useRef(p.format||(v=>v.toFixed(2)))"));
        assertTrue(patchedSlider.contains("toStr=n=>p.format?p.format(n):n.toFixed(2)"));
        assertEquals(patchedSlider, LunarLockerUiBundlePatcher.transformChunkedSliderFormatting(patchedSlider));

        String scroll = "const panel=e(\"div\",{display:\"flex\",minWidth:\"275px\",flex:\"1 1 auto\","
                + "alignItems:\"flex-start\",alignSelf:\"stretch\",flexDirection:\"column\","
                + "gap:\"var(--spacings-radii-3)\"});";
        String patchedScroll = LunarLockerUiBundlePatcher.transformChunkedCosmeticSettingsScroll(scroll);
        assertTrue(patchedScroll.contains("overflowY:\"auto\""));
        assertTrue(patchedScroll.contains("maxHeight:\"100%\""));
        assertEquals(patchedScroll, LunarLockerUiBundlePatcher.transformChunkedCosmeticSettingsScroll(patchedScroll));

        String index5 = "import{a as Z}from\"./index.js\";const other=1,"
                + "CardStyle=Sty(\"div\",{variants:{size:{md:{height:\"225px\",minWidth:\"185px\"}}}}),"
                + "RightOverlay=Sty(\"div\",{display:\"flex\",flexDirection:\"column\",alignItems:\"flex-start\","
                + "gap:\"4px\",position:\"absolute\",right:0,top:0}),"
                + "MenuBody=({cosmetic:c,isEquipped:q,lockedDiscordRewardText:l,onDiscordRewardCta:d,onSettings:s})=>"
                + "k.jsxs(k.Fragment,{children:[k.jsx(MenuEntry,{title:c.name,label:c.type}),"
                + "l==null&&k.jsx(MenuEntry,{label:\"Settings\",icon:k.jsx(CogIcon,{}),onClick:s})]}),"
                + "MenuWrap=({cosmetic:c,children:ch,onSettings:s,getDiscordRewardCtaText:gt,onDiscordRewardCta:ct,outfit:of})=>{"
                + "const[st,setSt]=ReactMod.useState({visible:!1,cachedCosmetic:c}),cur=st.cachedCosmetic;"
                + "return k.jsx(CtxMenu,{options:cur?k.jsx(MenuBody,{cosmetic:cur,isEquipped:!1,onSettings:()=>{}}):null,children:ch})},"
                + "CosTile=e=>{const{cosmetic:n,outfits:i,topRight:h}=e;"
                + "const x=k.jsx(Tip,{placement:\"right-start\",content:k.jsx(TipBox,{children:k.jsxs(\"div\",{style:{display:\"flex\"},"
                + "children:[k.jsx(Txt,{fontFamily:\"modern\",fontSize:\"14px\",color:\"var(--colors-neutrals-space-11)\","
                + "fontWeight:\"500\",children:\"Active in outfits:\"})]})})});"
                + "return k.jsx(MenuWrap,{cosmetic:n,children:k.jsx(Tile,{topLeft:x,topRight:h,children:n.display==null?"
                + "k.jsx(Mq,{id:`COSMETIC_MANNEQUIN_${n.id}`},`X`):null})})},"
                + "BadgeRender=w=>k.jsx(Tile,{topLeft:w.badge.description==null&&(w.grantedAt==null||w.grantedAt<=0)?void 0:"
                + "k.jsx(BadgeInfo,{ownedBadge:w}),children:k.jsx(BadgeImg,{ownedBadge:w,size:\"lg\"})},`BADGE_${w.badge.id}`),"
                + "EmoteRender=e=>k.jsx(Tile,{onMouseEnter:()=>{br.model.playEmote(`EMOTE_MANNEQUIN_${e.emote.id}`)}},"
                + "`EMOTE_${e.emote.id}`),"
                + "SortList=[{id:\"alpha-asc\",label:\"Alphabetical (A-Z)\",shortLabel:\"A-Z\",icon:null,sort:(a,b)=>0},"
                + "{id:\"new-first\",label:\"New first\",shortLabel:\"NEW\",icon:null,sort:(a,b)=>0},"
                + "{id:\"acquired-oldest\",label:\"Acquired (Oldest)\",shortLabel:\"ACQ\",icon:null,sort:(a,b)=>a.grantedAt==b.grantedAt?0:a.grantedAt>b.grantedAt?1:-1}],"
                + "useCosList=()=>{const getOpt=()=>SortList.find(q=>q.id===sr.current)??SortList[0],"
                + "cmpFn=(a,b)=>{const r1=prio(a),r2=prio(b);if(r1!=r2)return r1-r2;const fn=getOpt().sort;return fn?fn(a,b,st.current):0},"
                + "onSort=ReactMod.useCallback(val=>{sr.current=val,allRef.current=allRef.current.sort(cmpFn),setVis([...visRef.current].sort(cmpFn)),markSeen()},[setVis,markSeen,allRef]);"
                + "return{pageEmptyReason:0,handleCosmeticSort:onSort}},"
                + "Done=()=>k.jsx(Btn,{text:\"Finish\",type:\"secondary\",onClick:()=>{}}),"
                + "Screen=({cosmetics:e,cosmetic:n,onReturn:s})=>{const i=ReactMod.useRef(!1),"
                + "[o,c]=ReactMod.useState([]),[a,d]=ReactMod.useState(n),[u,l]=ReactMod.useState(null);"
                + "return k.jsx(Sec,{title:\"Cosmetic Settings\",children:o.length==0?"
                + "k.jsx(\"span\",{children:\"This cosmetic does not have any settings.\"}):o},\"COSMETIC_SETTINGS\")};"
                + "export{Screen as LockerView};";
        String patchedAppearance = LunarLockerUiBundlePatcher.transformChunkedCosmeticAppearanceIndex5(index5, fragment);
        assertTrue(patchedAppearance.contains("import{R as GomodLockerSlider}from\"./color-picker-option.js\";"));
        assertTrue(patchedAppearance.contains("GomodCosmeticAppearanceSettings=GomodCosmeticAppearanceCreate(ReactMod,k,Btn,GomodLockerSlider)"));
        assertTrue(patchedAppearance.contains("title:\"Appearance\",children:k.jsx(GomodCosmeticAppearanceSettings,{cosmeticId:n.id,cosmeticName:n.name,cosmeticType:n.type,fitId:u&&u.id})"));
        assertTrue(patchedAppearance.contains("o.length>0&&k.jsx(Sec,{title:\"Cosmetic Settings\",children:"));
        assertEquals(patchedAppearance, LunarLockerUiBundlePatcher.transformChunkedCosmeticAppearanceIndex5(patchedAppearance, fragment));

        String patchedFull = LunarLockerUiBundlePatcher.transformChunkedLockerExtrasIndex5(patchedAppearance, extrasFragment);
        assertTrue(patchedFull.contains("GomodLockerExtrasCreate(ReactMod,k,Tip,TipBox,Txt,MenuEntry)"));
        assertTrue(patchedFull.contains("MenuBody=({cosmetic:c,isEquipped:q,lockedDiscordRewardText:l,onDiscordRewardCta:d,onSettings:s,visible:gomodVisible})=>"));
        assertTrue(patchedFull.contains("l==null&&k.jsx(GomodResetContextItem,{kind:\"cosmetic\",id:c.id,visible:gomodVisible})"));
        assertTrue(patchedFull.contains("options:cur?k.jsx(MenuBody,{cosmetic:cur,visible:st.visible,"));
        assertTrue(patchedFull.contains("topLeft:x,topRight:k.jsxs(k.Fragment,{children:[h,k.jsx(GomodCosmeticOwnershipBadge,{cosmeticId:n.id})]}),children:n.display==null?"));
        assertTrue(patchedFull.contains("topRight:k.jsx(GomodCosmeticOwnershipBadge,{badgeId:w.badge.id}),children:"));
        assertTrue(patchedFull.contains("topRight:k.jsx(GomodCosmeticOwnershipBadge,{emoteId:e.emote.id,isFreeLunarPlus:e.isFreeLunarPlus,emoteLimited:e.emote.expireTime!=null&&e.emote.expireTime>0&&e.emote.expiresIn!=null}),onMouseEnter:"));
        assertTrue(patchedFull.contains("right:0,top:0,zIndex:20"));
        assertTrue(patchedFull.contains("minWidth:\"175px\""));
        assertTrue(patchedFull.contains("{id:\"edited-first\",label:\"Edited first\",shortLabel:\"EDIT\","));
        assertTrue(patchedFull.contains("if(getOpt().id===\"edited-first\"){const gomodLeft=GomodIsCosmeticEdited(a.id),gomodRight=GomodIsCosmeticEdited(b.id);if(gomodLeft!==gomodRight)return gomodLeft?-1:1}"));
        assertTrue(patchedFull.contains("onSort=ReactMod.useCallback(val=>{sr.current=val,val===\"edited-first\"&&GomodEditedItemsRefresh(),allRef.current=allRef.current.sort(cmpFn),setVis([...visRef.current].sort(cmpFn)),markSeen()},[setVis,markSeen,allRef]);ReactMod.useEffect(()=>GomodCosmeticSortSubscribe(()=>{sr.current===\"edited-first\"&&(allRef.current=allRef.current.sort(cmpFn),setVis([...visRef.current].sort(cmpFn)))}),[setVis,allRef]);"));
        assertEquals(patchedFull, LunarLockerUiBundlePatcher.transformChunkedLockerExtrasIndex5(patchedFull, extrasFragment));
    }

    @Test
    public void structuralChunkedBadgeStudioDiscoversArbitrarySymbolsInBothOrders() throws Exception {
        String extrasFragment = readChunkedExtrasFragment();
        String badgeStudioFragment = readChunkedBadgeStudioFragment();
        String source = ""
                + "import{r as Rx,j as Jx}from\"./index.js\";"
                + "const ModalComp=()=>null;"
                + "const MenuComp=()=>null;"
                + "const IconHelper=()=>null;"
                + "const IconEnum={PI_SETTINGS02_SOLID:1};"
                + "const CtxMenu=()=>null;"
                + "const MItem=()=>null;"
                + "const MDiv=()=>null;"
                + "const UnEq=()=>null;"
                + "const Eq=()=>null;"
                + "const Gear=()=>null;"
                + "const BridgeObj={};"
                + "const BtnComp=()=>null;"
                + "const TooltipComp=()=>null;"
                + "const TooltipBody=()=>null;"
                + "const TextComp=()=>null;"
                + "const ImgRaw=()=>null;"
                + "const BadgeCard=()=>null;"
                + "const InfoTag=()=>null;"
                + "const openCreate=e=>Jx.jsxs(ModalComp,{title:\"Create new Outfit\",onClose:e,width:\"532px\",logo:!1,children:[]});"
                + "const gearBtn=Jx.jsx(BtnComp,{icon:IconHelper(IconEnum.PI_SETTINGS02_SOLID,{width:\"20px\",height:\"20px\"})});"
                + "const tip=Jx.jsx(TooltipComp,{placement:\"right-start\",content:Jx.jsx(TooltipBody,{children:Jx.jsxs(\"div\",{style:{gap:\"4px\"},children:[Jx.jsx(TextComp,{fontFamily:\"modern\",fontSize:\"14px\",color:\"var(--colors-neutrals-space-11)\",fontWeight:\"500\",children:\"Active in outfits:\"})]})})});"
                + "fin=Jx.jsx(BtnComp,{text:\"Finish\",type:\"secondary\"}),"
                + "SettingsPanel=({cosmetics:e,cosmetic:n,onReturn:s})=>{const i=Rx.useRef(!1),[o,c]=Rx.useState([]),[a,d]=Rx.useState(n),[u,l]=Rx.useState(null);return null};"
                + "const CMenu=({cosmetic:c,isEquipped:eq,lockedDiscordRewardText:lk,onDiscordRewardCta:ct,onSettings:st})=>Jx.jsxs(Jx.Fragment,{children:[Jx.jsx(MItem,{title:c.name,label:c.type}),Jx.jsx(MDiv,{}),lk==null&&Jx.jsx(MItem,{label:eq?\"Unequip\":\"Equip\",icon:eq?Jx.jsx(UnEq,{}):Jx.jsx(Eq,{}),onClick:()=>BridgeObj.cosmetic.toggleCosmetic(c.id)}),lk==null&&Jx.jsx(MItem,{label:\"Settings\",icon:Jx.jsx(Gear,{}),onClick:st})]});"
                + "const CWrap=({cosmetic:c,children:ch,onSettings:st,getDiscordRewardCtaText:g,onDiscordRewardCta:ct,outfit:of})=>{const[stV,setStV]=Rx.useState({visible:!1,cachedCosmetic:c});return Jx.jsx(CtxMenu,{usePortal:!0,options:c?Jx.jsx(CMenu,{cosmetic:c,visible:!1,isEquipped:!1,onSettings:()=>{}}):null,children:ch})};"
                + "const Card=e=>{const{cosmetic:n,outfits:i,topRight:h}=e;return Jx.jsx(\"div\",{topLeft:null,topRight:h,children:n.display==null?null:null})};"
                + "const layerStyle={display:\"flex\",flexDirection:\"column\",alignItems:\"flex-start\",gap:\"4px\",position:\"absolute\",right:0,top:0};"
                + "const BadgeImg=p=>Jx.jsx(\"div\",{style:{width:\"100%\",height:\"100%\"},children:Jx.jsx(ImgRaw,{src:p.ownedBadge.badge.resource,alt:p.ownedBadge.badge.name,animated:p.ownedBadge.badge.animated,width:p.size==\"lg\"?\"64px\":\"16px\",height:p.size==\"lg\"?\"64px\":\"16px\"})});"
                + "const BList=(bArr,eqBadge)=>bArr.map((v0,i0)=>{const item=bArr[i0];return item?Jx.jsx(BadgeCard,{className:i0==0?\"badgesTour2\":void 0,name:item.badge.name,equipped:(eqBadge==null?void 0:eqBadge.id)==item.badge.id,selected:!1,onClick:()=>{},onDoubleClick:()=>{BridgeObj.badges.equip(item.badge.id)},topLeft:item.badge.description==null&&(item.grantedAt==null||item.grantedAt<=0)?void 0:Jx.jsx(InfoTag,{ownedBadge:item}),children:Jx.jsx(BadgeImg,{ownedBadge:item,size:\"lg\"})},`BADGE_${item.badge.id}`):null});"
                + "const sortOpts=[{id:\"new-first\",label:\"New first\",shortLabel:\"NEW\",icon:null,sort:(a,b)=>0},{id:\"acquired-oldest\",label:\"Acquired (Oldest)\",shortLabel:\"ACQ\",icon:null,sort:(a,b)=>0}],"
                + "getSel=()=>sortOpts.find(o=>o.id===sr.current)??sortOpts[0],cmp=(a,b)=>{const x=rank(a),y=rank(b);if(x!=y)return x-y;const f=getSel().sort;return f?f(a,b,sr.current):0};"
                + "const onSort=Rx.useCallback(val=>{sr.current=val,allRef.current=allRef.current.sort(cmp),setVis([...visRef.current].sort(cmp)),markSeen()},[setVis,markSeen,allRef]);return{pageEmptyReason:null};export{BList};";

        String extrasThenStudio = LunarLockerUiBundlePatcher.transformChunkedBadgeStudioIndex5(
                LunarLockerUiBundlePatcher.transformChunkedLockerExtrasIndex5(source, extrasFragment),
                badgeStudioFragment);
        assertTrue(extrasThenStudio.contains("\"data-gomod-badge-studio\":\"v2-chunked\""));
        assertTrue(extrasThenStudio.contains("window.lunar.gomodStudioOpen"));
        assertTrue(badgeStudioFragment.contains("cb=window.lunar,value=cb&&typeof cb.gomodIsLunarFullAccessEnabled===\"function\""));
        assertTrue(extrasThenStudio.contains("GomodUseBadgeFilter(p.ownedBadge.badge.id)"));
        assertTrue(extrasThenStudio.contains("GomodCustomBadgeImage,{badgeId:p.ownedBadge.badge.id"));
        assertTrue(extrasThenStudio.contains("GomodLockerItemContext,{kind:\"badge\",id:item.badge.id"));
        assertTrue(extrasThenStudio.contains("GomodStudioLauncher,{kind:\"badge\",item:item.badge}"));
        assertTrue(extrasThenStudio.contains("GomodCosmeticOwnershipBadge,{badgeId:item.badge.id}"));
        assertEquals(extrasThenStudio,
                LunarLockerUiBundlePatcher.transformChunkedBadgeStudioIndex5(extrasThenStudio, badgeStudioFragment));

        String studioThenExtras = LunarLockerUiBundlePatcher.transformChunkedLockerExtrasIndex5(
                LunarLockerUiBundlePatcher.transformChunkedBadgeStudioIndex5(source, badgeStudioFragment),
                extrasFragment);
        assertTrue(studioThenExtras.contains("GomodCosmeticOwnershipBadge,{badgeId:item.badge.id}"));
        assertTrue(studioThenExtras.contains("GomodStudioLauncher,{kind:\"badge\",item:item.badge}"));
        assertTrue(studioThenExtras.contains("GomodLockerItemContext,{kind:\"badge\",id:item.badge.id"));
        assertEquals(studioThenExtras,
                LunarLockerUiBundlePatcher.transformChunkedBadgeStudioIndex5(studioThenExtras, badgeStudioFragment));
    }

    @Test
    public void transformsInstalledChunkedLunarCachesWithoutErrors() throws Exception {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.trim().isEmpty()) {
            return;
        }
        Path uiDir = Paths.get(userHome, ".lunarclient", "ui");
        if (!Files.isDirectory(uiDir)) {
            return;
        }
        String fragment = readChunkedFragment();
        String extrasFragment = readChunkedExtrasFragment();
        String badgeStudioFragment = readChunkedBadgeStudioFragment();
        String[] hashes = new String[] {
                "43eb053f79d6a7ea3aff09c8c8177173017cdcb3",
                "85deb607f28f24769b366c6316f3142f0141703d"
        };
        int checked = 0;
        for (String hash : hashes) {
            Path staticDir = uiDir.resolve(hash).resolve("static");
            Path index5 = staticDir.resolve("index5.js");
            Path slider = staticDir.resolve("color-picker-option.js");
            Path windowBase = staticDir.resolve("window-base.js");
            if (!Files.isRegularFile(index5) || !Files.isRegularFile(slider) || !Files.isRegularFile(windowBase)) {
                continue;
            }
            String patchedIndex5 = LunarLockerUiBundlePatcher.transformChunkedBadgeStudioIndex5(
                    LunarLockerUiBundlePatcher.transformChunkedLockerExtrasIndex5(
                            LunarLockerUiBundlePatcher.transformChunkedCosmeticAppearanceIndex5(
                                    readUtf8(index5), fragment),
                            extrasFragment),
                    badgeStudioFragment);
            assertTrue(patchedIndex5.contains("\"data-gomod-cosmetic-appearance\":\"v11-chunked\""));
            assertTrue(patchedIndex5.contains("\"data-gomod-locker-extras\":\"v3-chunked\""));
            assertTrue(patchedIndex5.contains("\"data-gomod-badge-studio\":\"v2-chunked\""));
            assertTrue(patchedIndex5.contains("title:\"Appearance\""));
            assertTrue(patchedIndex5.contains("GomodResetContextItem,{kind:\"cosmetic\",id:"));
            assertTrue(patchedIndex5.contains("GomodCosmeticOwnershipBadge,{cosmeticId:"));
            assertTrue(patchedIndex5.contains("GomodCosmeticOwnershipBadge,{emoteId:"));
            assertTrue(patchedIndex5.contains("GomodCosmeticOwnershipBadge,{badgeId:"));
            assertTrue(patchedIndex5.contains("GomodUseBadgeFilter("));
            assertTrue(patchedIndex5.contains("GomodCustomBadgeImage,{badgeId:"));
            assertTrue(patchedIndex5.contains("GomodLockerItemContext,{kind:\"badge\",id:"));
            assertTrue(patchedIndex5.contains("GomodStudioLauncher,{kind:\"badge\",item:"));
            assertTrue(patchedIndex5.contains("right:0,top:0,zIndex:20"));
            assertTrue(patchedIndex5.contains("id:\"edited-first\",label:\"Edited first\",shortLabel:\"EDIT\""));
            assertTrue(patchedIndex5.contains("GomodCosmeticSortSubscribe("));
            assertFalse(patchedIndex5.contains("title:\"Cosmetic Settings\"") && !patchedIndex5.contains("o.length>0&&"));
            assertEquals(patchedIndex5, LunarLockerUiBundlePatcher.transformChunkedBadgeStudioIndex5(
                    LunarLockerUiBundlePatcher.transformChunkedLockerExtrasIndex5(
                            patchedIndex5, extrasFragment),
                    badgeStudioFragment));

            String patchedSlider = LunarLockerUiBundlePatcher.transformChunkedSliderFormatting(readUtf8(slider));
            assertTrue(patchedSlider.contains("e.format?e.format("));

            String patchedScroll = LunarLockerUiBundlePatcher.transformChunkedCosmeticSettingsScroll(readUtf8(windowBase));
            assertTrue(patchedScroll.contains("overflowY:\"auto\""));

            Path indexJs = staticDir.resolve("index.js");
            if (Files.isRegularFile(indexJs)) {
                String homeQueueFragment = readResource("/gomod/lunar-home-queue-ui-fragment.js");
                String patchedHome = LunarLockerUiBundlePatcher.transformHomeQueueIndex(
                        readUtf8(indexJs), homeQueueFragment);
                assertTrue(patchedHome.contains("\"data-gomod-home-queue\":\"v3\""));
                assertTrue(patchedHome.contains("r.useState({enabled:!1,"));
                assertTrue(patchedHome.contains("GomodMainMenuQueueButtons,{jsx:"));
                assertTrue(patchedHome.contains("gomodGetMainMenuQueueState"));
                assertTrue(patchedHome.contains("gomodQuickQueueBlitz"));
                assertEquals(patchedHome, LunarLockerUiBundlePatcher.transformHomeQueueIndex(
                        patchedHome, homeQueueFragment));
            }
            checked++;
        }
        assertTrue("Expected at least one chunked Lunar UI cache on disk", checked > 0);
    }

    @Test
    public void transformsSyntheticHomeQueueButtonsAndRemainsIdempotent() throws Exception {
        String fragment = readResource("/gomod/lunar-home-queue-ui-fragment.js");
        String synthetic = "const Home=()=>{R9.useEffect(()=>()=>{B7.home.radio.close()},[]),"
                + "return J2.jsx(\"div\",{style:{display:\"flex\",flexDirection:\"row\",flexWrap:\"wrap\","
                + "alignItems:\"center\",gap:\"8px\"},children:btns.map((it,idx)=>J2.jsx(BtnComp,"
                + "{halfWidth:idx>1&&btns.length>3,badge:it.badge,icon:it.icon,text:tr(it.id),styling:it.style,"
                + "onMouseDown:()=>{B7.onClick(it.id)}},it.id))})};export{Home};";

        String patched = LunarLockerUiBundlePatcher.transformHomeQueueIndex(synthetic, fragment);
        assertTrue(patched.contains("\"data-gomod-home-queue\":\"v3\""));
        assertTrue(patched.contains("r.useState({enabled:!1,"));
        assertTrue(patched.contains("J2.jsx(GomodMainMenuQueueButtons,{jsx:J2,react:R9,Button:BtnComp,bridge:B7})"));
        assertEquals(patched, LunarLockerUiBundlePatcher.transformHomeQueueIndex(patched, fragment));
    }

    private static String readUtf8(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String readChunkedExtrasFragment() throws Exception {
        return readResource("/gomod/lunar-locker-extras-chunked-ui-fragment.js");
    }

    @Test
    public void patchesOfficialLoadoutOnSyntheticAndRealChunkedBundles() throws Exception {
        String officialFragment = readChunkedOfficialLoadoutFragment();
        String synthetic = ""
                + "const Tip=()=>null,TipC=()=>null,Txt=()=>null,TitleB=()=>null,Row=()=>null,Col=()=>null,Filt=()=>null,PM=()=>null;"
                + "const Skn={SELF:1},Disp={DUMMY:2},Ctx={IN_COSMETIC_PLAYER_MODEL:3};"
                + "const x1=J.jsx(Tip,{placement:\"right-start\",content:J.jsx(TipC,{children:J.jsxs(\"div\",{style:{gap:4},"
                + "children:[J.jsx(Txt,{fontFamily:\"modern\",fontSize:\"14px\",color:\"var(--colors-neutrals-space-11)\",fontWeight:\"500\",children:\"Active in outfits:\"})]})})});"
                + "const Wrap=({cosmetic:c,children:ch,onSettings:os,getDiscordRewardCtaText:gd,onDiscordRewardCta:od,outfit:of})=>{"
                + "const[st,setSt]=R.useState({visible:!1,cachedCosmetic:c});"
                + "return{options:c?J.jsx(Menu,{cosmetic:c,isEquipped:!0}):null};};"
                + "const YL=p=>J.jsx(TitleB,{canResize:!0,name:\"Your Loadout\",icon:p.icon,onHelp:p.onHelp});"
                + "const Dmy=()=>J.jsx(PM,{id:\"TEST_PLAYER_DUMMY\",skin:Skn.SELF,display:Disp.DUMMY,cosmetics:cs,renderContext:Ctx.IN_COSMETIC_PLAYER_MODEL,zoom:.65});"
                + "const Tab=()=>{return J.jsxs(Row,{children:[J.jsxs(Col,{children:[J.jsx(Filt,{activeType:0})]})]});};"
                + "const Tab2=()=>J.jsx(Row,{children:null});"
                + ";export{Tab};";

        String patched = LunarLockerUiBundlePatcher.transformChunkedOfficialLoadoutIndex5(synthetic, officialFragment);
        assertTrue(patched.contains("\"data-gomod-official-loadout\":\"v2-chunked\""));
        assertTrue(patched.contains("YL=p=>J.jsx(GomodYourLoadoutTitleBar,{TitleBar:TitleB,icon:p.icon,onHelp:p.onHelp})"));
        assertTrue(patched.contains("J.jsxs(GomodLockerRow,{base:Row,"));
        assertTrue(patched.contains("J.jsx(GomodLockerRow,{base:Row,"));
        assertTrue(patched.contains("GomodOfficialLoadoutCreate(R,J,Tip,TipC,Txt,PM,Skn,Disp,Ctx)"));
        assertEquals("official loadout transform must be idempotent",
                patched, LunarLockerUiBundlePatcher.transformChunkedOfficialLoadoutIndex5(patched, officialFragment));

        String[] hashes = {
                "85deb607f28f24769b366c6316f3142f0141703d",
                "43eb053f79d6a7ea3aff09c8c8177173017cdcb3"
        };
        for (String hash : hashes) {
            Path index5 = Paths.get(System.getProperty("user.home"), ".lunarclient", "ui", hash, "static", "index5.js");
            if (!Files.exists(index5)) continue;
            String raw = new String(Files.readAllBytes(index5), StandardCharsets.UTF_8);
            String realPatched = LunarLockerUiBundlePatcher.transformChunkedOfficialLoadoutIndex5(raw, officialFragment);
            assertTrue("official loadout marker missing for " + hash,
                    realPatched.contains("\"data-gomod-official-loadout\":\"v2-chunked\""));
            assertTrue("Your Loadout wrapper missing for " + hash,
                    realPatched.contains("GomodYourLoadoutTitleBar,{TitleBar:"));
            assertTrue("Locker row wrapper missing for " + hash,
                    realPatched.contains("GomodLockerRow,{base:"));
            assertEquals("real bundle transform must be idempotent for " + hash,
                    realPatched, LunarLockerUiBundlePatcher.transformChunkedOfficialLoadoutIndex5(realPatched, officialFragment));
        }
    }

    @Test
    public void patchesFitsActionsOnRealChunkedBundleAndIsIdempotent() throws Exception {
        String fitsFragment = readResource("/gomod/lunar-fits-actions-chunked-ui-fragment.js");
        Path realIndex5 = Paths.get(System.getProperty("user.home"),
                ".lunarclient", "ui", "85deb607f28f24769b366c6316f3142f0141703d", "static", "index5.js");
        if (!Files.isRegularFile(realIndex5)) return;
        String realSource = readUtf8(realIndex5);
        String patched = LunarLockerUiBundlePatcher.transformChunkedFitsActionsIndex5(realSource, fitsFragment);
        assertTrue(patched.contains("\"data-gomod-fits-actions\":\"v1-chunked\""));
        assertTrue(patched.contains("GomodRandomFitButton,{fullAccess:"));
        assertTrue(patched.contains("GomodFitShareButton,{fit:"));
        assertTrue(patched.contains("GomodFitShareDialog,{fit:"));
        assertTrue(patched.contains("GomodReceivedFitsNotice,{state:gomodFitColors}"));
        assertEquals("chunked fits actions transform must be idempotent",
                patched, LunarLockerUiBundlePatcher.transformChunkedFitsActionsIndex5(patched, fitsFragment));
    }

    private static String readChunkedOfficialLoadoutFragment() throws Exception {
        return readResource("/gomod/lunar-official-loadout-chunked-ui-fragment.js");
    }

    private static String readChunkedBadgeStudioFragment() throws Exception {
        return readResource("/gomod/lunar-studio-chunked-ui-fragment.js");
    }

    private static String readChunkedFragment() throws Exception {
        return readResource("/gomod/lunar-cosmetic-appearance-chunked-ui-fragment.js");
    }

    private static String readResource(String path) throws Exception {
        try (InputStream input = LunarLockerUiBundlePatcher.class.getResourceAsStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }
}
