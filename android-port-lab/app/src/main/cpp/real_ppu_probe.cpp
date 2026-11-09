// Copyright (c) 2026. Experimental PPC64 startup tracing for user-owned PS3 ELF files.
// An intentionally bounded subset interpreter, NOT a PS3 emulator or a game boot.
// The guest's ELF is loaded by the user on Android. No game assets enter public CI.
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>
#ifdef __ANDROID__
#include <jni.h>
#endif

namespace realppu {
constexpr uint64_t LIMIT = 48ull * 1024 * 1024;
constexpr unsigned MAX_STEPS = 50000;
uint16_t be16(const uint8_t* p) {return (uint16_t(p[0])<<8)|p[1];}
uint32_t be32(const uint8_t* p) {return (uint32_t(p[0])<<24)|(uint32_t(p[1])<<16)|(uint32_t(p[2])<<8)|p[3];}
uint64_t be64(const uint8_t* p) {return (uint64_t(be32(p))<<32)|be32(p+4);}
int32_t sext(uint32_t n,unsigned width) {return int32_t(n<<(32-width))>>(32-width);}
bool inrange(uint64_t p,uint64_t n,uint64_t max) {return p<=max && n<=max-p;}
uint64_t rol64(uint64_t x,unsigned n) {n &= 63; return n?((x<<n)|(x>>(64-n))):x;}
std::string hx(uint64_t n) {char b[32];std::snprintf(b,sizeof b,"0x%llX",(unsigned long long)n);return b;}
struct Seg {uint64_t addr,off,filesz,memsz;uint32_t flags;};
struct Memory {
  std::vector<uint8_t> image;
  std::vector<Seg> segs;
  Memory():image(size_t(LIMIT),0){}
  bool exec(uint64_t a) const {
    for(auto& s:segs)if((s.flags&1) && a>=s.addr && a-s.addr<s.memsz &&
       inrange(a,4,s.addr+s.memsz))return true;
    return false;
  }
  bool get(uint64_t p,int n,uint64_t& out) const {
    if(!inrange(p,n,image.size()))return false;
    out=0;for(int i=0;i<n;i++)out=(out<<8)|image[size_t(p+i)];
    return true;
  }
  bool put(uint64_t p,int n,uint64_t v) {
    if(!inrange(p,n,image.size()))return false;
    for(int i=n-1;i>=0;i--){image[size_t(p+i)]=uint8_t(v);v>>=8;}
    return true;
  }
};
struct CPU {
  uint64_t r[32]{},pc{},lr{},ctr{},xer{},instructions{},reads{},writes{},branches{},calls{};
  uint32_t cr{};
};

struct Import {
  uint32_t stub, nid;
  std::string module;
};
uint32_t guest32(const Memory& m, uint64_t addr) {
  uint64_t value=0;
  return m.get(addr,4,value)?uint32_t(value):0;
}
std::vector<Import> read_imports(const Memory& m,uint64_t prx_va) {
  std::vector<Import> imports;
  if(!prx_va || guest32(m,prx_va+4)!=0x1b434cecu)return imports;
  const uint32_t first=guest32(m,prx_va+24), last=guest32(m,prx_va+28);
  if(!first||last<=first||last-first>16384)return imports;
  for(uint64_t rec=first;rec<last;) {
    uint64_t size=0,count=0;
    if(!m.get(rec,1,size)||size<0x2c || !inrange(rec,size,last))break;
    if(!m.get(rec+6,2,count)||count>1024)break;
    uint32_t str=guest32(m,rec+16);
    uint32_t nids=guest32(m,rec+20);
    uint32_t stubs=guest32(m,rec+24);
    std::string module;
    for(unsigned c=0;c<64;c++) {
      uint64_t chr=0;
      if(!m.get(uint64_t(str)+c,1,chr)||!chr)break;
      if(chr<32||chr>126){module.clear();break;}
      module+=char(chr);
    }
    if(!module.empty()&&nids&&stubs) {
      for(unsigned i=0;i<count && imports.size()<2048;i++){
        uint64_t atnid=uint64_t(nids)+uint64_t(i)*4;
        uint64_t atstub=uint64_t(stubs)+uint64_t(i)*4;
        if(!inrange(atnid,4,LIMIT)||!inrange(atstub,4,LIMIT))break;
        uint32_t nid=guest32(m,atnid),stub=guest32(m,atstub);
        if(stub && nid && m.exec(stub))imports.push_back({stub,nid,module});
      }
    }
    rec+=size;
  }
  return imports;
}
bool import_hle(const Import& imp,CPU& c,Memory& m,std::string& event,std::string& stop) {
  // First real inFAMOUS CRT import: sysPrxForUser::sys_initialize_tls.
  // Model the PS3 per-thread TLS image and r13 register, not an arbitrary success.
  if(imp.module=="sysPrxForUser" && imp.nid==0x744680a2u) {
    uint64_t src=c.r[4], fileBytes=c.r[5], tlsBytes=c.r[6];
    constexpr uint64_t TLS_BASE=0x2d00000;
    constexpr uint64_t TLS_SLOT=0x40000;
    if(!src || fileBytes>tlsBytes || tlsBytes==0 || tlsBytes>0x10000 ||
       !inrange(src,fileBytes,LIMIT) || !inrange(TLS_BASE,TLS_SLOT,LIMIT)) {
      stop="sys_initialize_tls: invalid TLS arguments (address "+hx(src)+
        ", file bytes "+std::to_string(fileBytes)+
        ", memory bytes "+std::to_string(tlsBytes)+")";
      return false;
    }
    if(c.r[13]!=0) {
      stop="sys_initialize_tls: r13 already set unexpectedly";
      return false;
    }
    std::memset(m.image.data()+TLS_BASE,0,size_t(TLS_SLOT));
    std::memcpy(m.image.data()+TLS_BASE+0x30,m.image.data()+src,size_t(fileBytes));
    // RPCS3's layout: 0x30-byte thread system prefix, TLS image, then zero-fill.
    // PPC r13 points to the thread's ABI TLS anchor (+0x7000+0x30).
    c.r[13]=TLS_BASE+0x7000+0x30;
    if(!m.put(c.r[1]+40,8,c.r[2])) {
      stop="sys_initialize_tls: could not preserve caller TOC on stack";
      return false;
    }
    event="[HLE] sysPrxForUser::sys_initialize_tls NID 0x744680A2 "+
      std::string("initialized ")+std::to_string(tlsBytes)+
      " guest TLS bytes; r13="+hx(c.r[13]);
    // A branch-with-link to the import stub saved the return address in LR.
    // Firmware returns to LR; do not execute the unresolved trampoline bytes.
    c.pc=c.lr;
    return true;
  }
  stop="Firmware HLE handler missing for "+imp.module+" NID "+hx(imp.nid)+
    " (guest stub "+hx(imp.stub)+")";
  return false;
}

void crcmp(CPU& c,unsigned field,uint64_t a,uint64_t b,bool uns=false) {
  int v=uns?(a<b?8:a>b?4:2):(int64_t(a)<int64_t(b)?8:int64_t(a)>int64_t(b)?4:2);
  unsigned sh=28-((field&7)*4);c.cr=(c.cr&~(0xfu<<sh))|(uint32_t(v)<<sh);
}
bool crbit(const CPU& c,unsigned i) {return ((c.cr>>(31-(i&31)))&1)!=0;}

std::string run(const uint8_t* file,size_t n) {
  std::ostringstream o;
  o<<"REAL PS3 EBOOT STARTUP TRACE\n";
  o<<"This traces actual guest instructions; it does not make inFAMOUS playable.\n";
  if(!file||n<64||std::memcmp(file,"\x7f" "ELF",4)||file[4]!=2||file[5]!=2||be16(file+18)!=21)
    return "FAIL: expected big-endian ELF64 PowerPC executable";
  const uint64_t entry=be64(file+24),ph=be64(file+32);
  const unsigned entsz=be16(file+54),count=be16(file+56);
  if(entsz!=56||count==0||count>64||!inrange(ph,uint64_t(entsz)*count,n))
    return "FAIL: malformed program-header table";
  Memory m;
  uint64_t prxVa=0, tlsVa=0, tlsFileSize=0, tlsMemSize=0;
  for(unsigned i=0;i<count;i++) {
    const uint8_t* p=file+ph+i*entsz;
    if(be32(p)==0x60000002u)prxVa=be64(p+16);
    if(be32(p)==7u) {
      tlsVa=be64(p+16);tlsFileSize=be64(p+32);tlsMemSize=be64(p+40);
    }
    if(be32(p)!=1)continue;
    Seg s{be64(p+16),be64(p+8),be64(p+32),be64(p+40),be32(p+4)};
    if(!s.memsz)continue;
    if(s.filesz>s.memsz||!inrange(s.off,s.filesz,n)||!inrange(s.addr,s.memsz,LIMIT))
      return "FAIL: unsafe PT_LOAD mapping or truncated ELF";
    std::memcpy(m.image.data()+s.addr,file+s.off,size_t(s.filesz));
    m.segs.push_back(s);
  }
  if(m.segs.size()<2)return "FAIL: expected executable and data PT_LOAD segments";
  uint64_t pc=0,toc=0;
  // PS3 PPU uses a pair of 32-bit address and TOC words at e_entry.
  if(!m.get(entry,4,pc)||!m.get(entry+4,4,toc)||!m.exec(pc))
    return "FAIL: e_entry did not resolve to executable PPU code";
  const auto imports=read_imports(m,prxVa);
  CPU c;c.pc=pc;c.r[1]=0x2f00000;c.r[2]=toc;
  // RPCS3 process-entry ABI: r3..6=argc/argv/envp/etc; r7..10 hold the
  // main thread ID and ELF TLS template address/filesize/memsize.
  c.r[3]=1;c.r[7]=0x1000000;c.r[8]=tlsVa;c.r[9]=tlsFileSize;c.r[10]=tlsMemSize;
  c.r[13]=0; // sys_initialize_tls must create the actual thread-local state.
  unsigned hleCalls=0;
  o<<"Parsed firmware imports: "<<imports.size()<<"\n";
  o<<"Guest initial TLS template: addr="<<hx(tlsVa)
   <<" filesz="<<tlsFileSize<<" memsz="<<tlsMemSize<<"\n";
  o<<"Loaded "<<m.segs.size()<<" nonempty PT_LOAD segments; bytes="<<n<<"\n"
   <<"Entry descriptor "<<hx(entry)<<" -> first code "<<hx(pc)<<" TOC "<<hx(toc)<<"\n"
   <<"Initial stack "<<hx(c.r[1])<<" (sandboxed)\n";
  std::string stop;
  for(unsigned step=0;step<MAX_STEPS;step++) {
    // PS3 PT_SCE_PROC_PARAM points at import NIDs and their linker stubs.
    // Intercept the *stub address*, never an invalid trampoline instruction.
    auto imp=std::find_if(imports.begin(),imports.end(),
             [&](const Import& candidate){return candidate.stub==c.pc;});
    if(imp!=imports.end()) {
      std::string event;
      if(!import_hle(*imp,c,m,event,stop))break;
      if(hleCalls<12)o<<event<<"\n";
      ++hleCalls;
      continue;
    }
    if(!m.exec(c.pc)){stop="Unresolved guest code/import target "+hx(c.pc);break;}
    uint64_t raw=0;if(!m.get(c.pc,4,raw)){stop="Instruction fetch out of memory";break;}
    uint32_t ins=uint32_t(raw),op=ins>>26;
    unsigned rt=(ins>>21)&31,ra=(ins>>16)&31,rb=(ins>>11)&31;
    uint64_t here=c.pc,next=here+4;
    bool ok=true,memok=true;
    auto base=[&](unsigned x)->uint64_t{return x?c.r[x]:0;};
    auto load=[&](uint64_t addr,int bytes)->uint64_t {
      uint64_t v=0;if(!m.get(addr,bytes,v))memok=false;else c.reads++;return v;
    };
    auto store=[&](uint64_t addr,int bytes,uint64_t v){
      if(!m.put(addr,bytes,v))memok=false;else c.writes++;
    };
    int64_t imm=int16_t(ins&65535);
    switch(op) {
    case 14:c.r[rt]=base(ra)+imm;break; // addi
    case 15:c.r[rt]=base(ra)+imm*65536;break; // addis
    case 24:c.r[ra]=c.r[rt]|(ins&65535);break;
    case 25:c.r[ra]=c.r[rt]|(uint64_t(ins&65535)<<16);break;
    case 26:c.r[ra]=c.r[rt]^(ins&65535);break;
    case 27:c.r[ra]=c.r[rt]^(uint64_t(ins&65535)<<16);break;
    case 28:c.r[ra]=c.r[rt]&(ins&65535);crcmp(c,0,c.r[ra],0);break;
    case 29:c.r[ra]=c.r[rt]&(uint64_t(ins&65535)<<16);crcmp(c,0,c.r[ra],0);break;
    case 32:c.r[rt]=load(base(ra)+imm,4);break;
    case 33:c.r[rt]=load(base(ra)+imm,4);c.r[ra]=base(ra)+imm;break;
    case 34:c.r[rt]=load(base(ra)+imm,1);break;
    case 36:store(base(ra)+imm,4,c.r[rt]);break;
    case 37:store(base(ra)+imm,4,c.r[rt]);c.r[ra]=base(ra)+imm;break;
    case 38:store(base(ra)+imm,1,c.r[rt]);break;
    case 40:c.r[rt]=load(base(ra)+imm,2);break;
    case 42:c.r[rt]=int16_t(load(base(ra)+imm,2));break;
    case 44:store(base(ra)+imm,2,c.r[rt]);break;
    case 58:{
      uint64_t addr=base(ra)+sext(ins&0xfffc,16);
      if((ins&3)==0||(ins&3)==1){c.r[rt]=load(addr,8);if(ins&1)c.r[ra]=addr;}
      else if((ins&3)==2)c.r[rt]=int32_t(load(addr,4));else ok=false;
      break;
    }
    case 62:{
      uint64_t addr=base(ra)+sext(ins&0xfffc,16);
      if((ins&3)==0||(ins&3)==1){store(addr,8,c.r[rt]);if(ins&1)c.r[ra]=addr;}
      else ok=false;break;
    }
    case 10:crcmp(c,rt>>2,(ins&0x10000)?c.r[ra]:uint32_t(c.r[ra]),ins&65535,true);break;
    case 11:crcmp(c,rt>>2,(ins&0x10000)?c.r[ra]:uint64_t(int64_t(int32_t(c.r[ra]))),uint64_t(imm));break;
    case 18:{
      int64_t disp=sext(ins&0x03fffffc,26);
      if(ins&1){c.lr=here+4;c.calls++;}
      next=(ins&2)?uint64_t(disp):uint64_t(here+disp);c.branches++;break;
    }
    case 16:{
      unsigned bo=rt,bi=ra;bool counter=true,condition=true;
      if(!(bo&4)){--c.ctr;counter=((c.ctr!=0)!=bool(bo&2));}
      if(!(bo&16))condition=(crbit(c,bi)==bool(bo&8));
      if(counter&&condition){int64_t disp=sext(ins&0xfffc,16);next=(ins&2)?uint64_t(disp):uint64_t(here+disp);c.branches++;}
      if(ins&1){c.lr=here+4;c.calls++;}break;
    }
    case 19:{
      unsigned xo=(ins>>1)&1023;
      if(xo==16||xo==528){
        unsigned bo=rt,bi=ra;bool counter=true,condition=true;
        if(!(bo&4)){--c.ctr;counter=((c.ctr!=0)!=bool(bo&2));}
        if(!(bo&16))condition=(crbit(c,bi)==bool(bo&8));
        uint64_t dest=(xo==16?c.lr:c.ctr)&~uint64_t(3);
        if(counter&&condition){next=dest;c.branches++;}
        if(ins&1){c.lr=here+4;c.calls++;}
      }else ok=false;break;
    }
    case 31:{
      unsigned xo=(ins>>1)&1023;
      unsigned spr=(rb<<5)|ra;
      switch(xo){
      case 339:if(spr==8)c.r[rt]=c.lr;else if(spr==9)c.r[rt]=c.ctr;else if(spr==1)c.r[rt]=c.xer;else ok=false;break;
      case 467:if(spr==8)c.lr=c.r[rt];else if(spr==9)c.ctr=c.r[rt];else if(spr==1)c.xer=c.r[rt];else ok=false;break;
      case 444:c.r[ra]=c.r[rt]|c.r[rb];break;
      case 28:c.r[ra]=c.r[rt]&c.r[rb];break;
      case 316:c.r[ra]=c.r[rt]^c.r[rb];break;
      case 266:c.r[rt]=c.r[ra]+c.r[rb];break;
      case 40:c.r[rt]=c.r[rb]-c.r[ra];break;
      case 23:c.r[rt]=load(base(ra)+c.r[rb],4);break;
      case 21:c.r[rt]=load(base(ra)+c.r[rb],8);break;
      case 151:store(base(ra)+c.r[rb],4,c.r[rt]);break;
      case 149:store(base(ra)+c.r[rb],8,c.r[rt]);break;
      case 824:c.r[ra]=uint64_t(int64_t(int32_t(c.r[rt]))>>((ins>>11)&31));break;
      case 0:crcmp(c,rt>>2,(ins&0x200000)?c.r[ra]:uint64_t(int64_t(int32_t(c.r[ra]))),(ins&0x200000)?c.r[rb]:uint64_t(int64_t(int32_t(c.r[rb]))));break;
      case 32:crcmp(c,rt>>2,(ins&0x200000)?c.r[ra]:uint32_t(c.r[ra]),(ins&0x200000)?c.r[rb]:uint32_t(c.r[rb]),true);break;
      default:ok=false;break;
      }
      break;
    }
    case 30:{
      unsigned xo=(ins>>2)&7;
      unsigned sh=((ins>>11)&31)|((ins&2)?32:0),mb=((ins>>6)&31)|((ins&0x20)?32:0);
      if(xo==0)c.r[ra]=rol64(c.r[rt],sh)&(UINT64_MAX>>mb);
      else ok=false;break;
    }
    case 21:{
      unsigned sh=(ins>>11)&31,mb=(ins>>6)&31,me=(ins>>1)&31;
      uint32_t v=uint32_t(c.r[rt]);v=sh?((v<<sh)|(v>>(32-sh))):v;
      uint32_t mask=0;
      for(unsigned bit=0;bit<32;bit++)if((mb<=me&&bit>=mb&&bit<=me)||(mb>me&&(bit>=mb||bit<=me)))
        mask|=(1u<<(31-bit));
      c.r[ra]=v&mask;break;
    }
    case 17:stop="PS3 LV2 syscall needs HLE implementation";ok=false;break;
    default:ok=false;break;
    }
    if(step<48)o<<"["<<step<<"] "<<hx(here)<<" "<<hx(ins)<<(ok?" executed":" unsupported")<<"\n";
    if(!ok){if(stop.empty())stop="Unsupported PPC instruction: opcode "+std::to_string(op)+" at "+hx(here);break;}
    if(!memok){stop="Unmapped guest memory access at "+hx(here);break;}
    c.pc=next;c.instructions++;
  }
  if(stop.empty())stop="Instruction budget reached";
  o<<"\nGuest firmware HLE calls handled: "<<hleCalls<<"\n"
   <<"Real PPU instructions executed: "<<c.instructions<<"\n"
   <<"Branches: "<<c.branches<<"; linked calls: "<<c.calls<<"\n"
   <<"Guest memory reads: "<<c.reads<<"; writes: "<<c.writes<<"\n"
   <<"Final guest PC: "<<hx(c.pc)<<"\n"
   <<"Stop: "<<stop<<"\n"
   <<"Status: PARTIAL GUEST STARTUP, NOT GAME BOOT\n"
   <<"Next: resolve PS3 imports, LV2 syscalls, SPU, RSX and game filesystem.\n";
  return o.str();
}
} // namespace realppu

#ifdef __ANDROID__
extern "C" JNIEXPORT jstring JNICALL
Java_com_hcode91_infamousprobe_MainActivity_nativeRealPpuProbe(JNIEnv* env,jclass,jbyteArray input) {
  if(!input)return env->NewStringUTF("No ELF input");
  jsize n=env->GetArrayLength(input);
  if(n<64||n>32*1024*1024)return env->NewStringUTF("ELF outside 64-byte to 32-MiB bounds");
  std::vector<uint8_t> bytes(size_t(n),0);
  env->GetByteArrayRegion(input,0,n,reinterpret_cast<jbyte*>(bytes.data()));
  if(env->ExceptionCheck())return nullptr;
  std::string report=realppu::run(bytes.data(),bytes.size());
  return env->NewStringUTF(report.c_str());
}
#endif

#ifdef REAL_PPU_PROBE_HOST
#include <fstream>
#include <iterator>
int main(int argc,char** argv){
  if(argc!=2)return 2;
  std::ifstream in(argv[1],std::ios::binary);
  if(!in)return 2;
  std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)),{});
  std::puts(realppu::run(bytes.data(),bytes.size()).c_str());
  return 0;
}
#endif
