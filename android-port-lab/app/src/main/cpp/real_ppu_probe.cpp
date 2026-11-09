// Copyright (c) 2026. Experimental PPC64 startup tracing for user-owned PS3 ELF files.
// An intentionally bounded subset interpreter, NOT a PS3 emulator or a game boot.
// The guest's ELF is loaded by the user on Android. No game assets enter public CI.
#include <algorithm>
#include <chrono>
#include <thread>
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
// The initial main PPU thread ID is passed as r7 by the launcher. The same
// ID is used by startup lwmutex ownership and thread-get-id HLE.
constexpr uint64_t MAIN_PPU_THREAD_ID = 0x01000000ull;
uint16_t be16(const uint8_t* p) {return (uint16_t(p[0])<<8)|p[1];}
uint32_t be32(const uint8_t* p) {return (uint32_t(p[0])<<24)|(uint32_t(p[1])<<16)|(uint32_t(p[2])<<8)|p[3];}
uint64_t be64(const uint8_t* p) {return (uint64_t(be32(p))<<32)|be32(p+4);}
int32_t sext(uint32_t n,unsigned width) {return int32_t(n<<(32-width))>>(32-width);}
bool inrange(uint64_t p,uint64_t n,uint64_t max) {return p<=max && n<=max-p;}
uint64_t rol64(uint64_t x,unsigned n) {n &= 63; return n?((x<<n)|(x>>(64-n))):x;}
std::string hx(uint64_t n) {char b[32];std::snprintf(b,sizeof b,"0x%llX",(unsigned long long)n);return b;}
struct Seg {uint64_t addr,off,filesz,memsz;uint32_t flags;};
struct Memory {
  struct LwMutex {
    uint32_t guestAddr, id, attributes, owner, recursion;
    bool alive;
  };
  std::vector<LwMutex> mutexes;
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
const char* known_import_name(const Import& i) {
  if (i.module != "sysPrxForUser") return "unknown import";
  switch (i.nid) {
    case 0x744680a2u: return "sys_initialize_tls";
    case 0x8461e528u: return "sys_time_get_system_time";
    case 0x2f85c0efu: return "sys_lwmutex_create";
    case 0x1573dc3fu: return "sys_lwmutex_lock";
    case 0xaeb78725u: return "sys_lwmutex_trylock";
    case 0x1bc200f4u: return "sys_lwmutex_unlock";
    case 0xc3476d0cu: return "sys_lwmutex_destroy";
    case 0x350d454eu: return "sys_ppu_thread_get_id";
    case 0xa3e3be68u: return "sys_ppu_thread_once";
    case 0x6bf66ea7u: return "_sys_memcpy";
    case 0x68b9b011u: return "_sys_memset";
    case 0xbdb18f83u: return "_sys_malloc";
    default: return "unmapped function NID";
  }
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
    // Caller TOC is saved centrally by the import bridge before this handler.
    event="[HLE] sysPrxForUser::sys_initialize_tls NID 0x744680A2 "+
      std::string("initialized ")+std::to_string(tlsBytes)+
      " guest TLS bytes; r13="+hx(c.r[13]);
    // A branch-with-link to the import stub saved the return address in LR.
    // Firmware returns to LR; do not execute the unresolved trampoline bytes.
    c.pc=c.lr;
    return true;
  }
  if (imp.module=="sysPrxForUser" && imp.nid==0x8461e528u) {
    // sys_time_get_system_time() has no arguments, returns u64 microseconds.
    // RPCS3: get_guest_system_time() uses a monotonic host clock; an absolute
    // wall-clock epoch or a constant zero would be incorrect here.
    // Clock is a process-wide monotonic source, not simulated instruction count.
    const auto now=std::chrono::steady_clock::now().time_since_epoch();
    const uint64_t micros=uint64_t(
      std::chrono::duration_cast<std::chrono::microseconds>(now).count());
    c.r[3]=micros;
    c.pc=c.lr; // return from the import stub, preserving LR and caller TOC
    event="[HLE] sys_time_get_system_time NID 0x8461E528: "
          "monotonic clock="+std::to_string(micros)+" usec";
    return true;
  }
  if (imp.module=="sysPrxForUser" && imp.nid==0x350d454eu) {
    // sys_ppu_thread_get_id(sys_ppu_thread_t* out)
    // RPCS3 rpcs3/Emu/Cell/Modules/sys_ppu_thread_.cpp:
    //   *thread_id = ppu.id; return CELL_OK;
    // A sys_ppu_thread_t is 64-bit; PS3 PPC ABI stores it big-endian.
    const uint64_t out=c.r[3];
    if(!out || (out&7u) || !inrange(out,8,LIMIT)) {
      stop="sys_ppu_thread_get_id: invalid 64-bit guest output pointer "+hx(out);
      return false;
    }
    if(!m.put(out,8,MAIN_PPU_THREAD_ID)) {
      stop="sys_ppu_thread_get_id: failed to write main thread ID at "+hx(out);
      return false;
    }
    ++c.writes;
    c.r[3]=0; // CELL_OK, not the thread ID itself.
    c.pc=c.lr;
    event="[HLE] sys_ppu_thread_get_id NID 0x350D454E: "
          "wrote 64-bit thread_id="+hx(MAIN_PPU_THREAD_ID)+" at "+hx(out);
    return true;
  }
  if (imp.module=="sysPrxForUser") {
    // Verified NID mapping: RPCS3 PPUFunction.cpp, sysPrxForUser import table.
    // Handle the guest's 24-byte lightweight-mutex structure using big-endian
    // fields and preserve a live mutex registry. No fake "always success" stub.
    if (imp.nid==0x2f85c0efu) { // sys_lwmutex_create(mutex*, attributes*)
      const uint64_t mutex=c.r[3], attr=c.r[4];
      if (!mutex || !attr || !inrange(mutex,24,LIMIT) ||
          !inrange(attr,16,LIMIT)) {
        stop="sys_lwmutex_create: invalid guest addresses mutex="+hx(mutex)+
             ", attr="+hx(attr);
        return false;
      }
      const uint32_t protocol=guest32(m,attr), recursive=guest32(m,attr+4);
      // Match PS3 sys_sync: FIFO=1, PRIORITY=2, RETRY=4;
      // recursive=0x10, non-recursive=0x20.
      if ((protocol!=1 && protocol!=2 && protocol!=4) ||
          (recursive!=0x10 && recursive!=0x20)) {
        stop="sys_lwmutex_create: unsupported guest attributes protocol="+
             hx(protocol)+", recursive="+hx(recursive)+
             ", mutex="+hx(mutex)+", attr="+hx(attr);
        return false;
      }
      if (m.mutexes.size()>=128) {
        stop="sys_lwmutex_create: per-probe mutex registry exhausted";
        return false;
      }
      for (const auto& existing:m.mutexes) {
        if (existing.guestAddr==uint32_t(mutex) && existing.alive) {
          stop="sys_lwmutex_create: guest tried to recreate a live mutex";
          return false;
        }
      }
      uint32_t id=0x95000100u+uint32_t(m.mutexes.size())*0x100u;
      // sys_lwmutex_t: u32 owner, waiter, attributes, recursion, sleep_queue, pad.
      bool wrote=m.put(mutex+0,4,0xffffffffu)
              && m.put(mutex+4,4,0)
              && m.put(mutex+8,4,recursive|protocol)
              && m.put(mutex+12,4,0)
              && m.put(mutex+16,4,id)
              && m.put(mutex+20,4,0);
      if (!wrote) {stop="sys_lwmutex_create: writing guest mutex failed";return false;}
      m.mutexes.push_back({uint32_t(mutex),id,recursive|protocol,0xffffffffu,0,true});
      c.writes+=6;c.r[3]=0;c.pc=c.lr;
      event="[HLE] sys_lwmutex_create NID 0x2F85C0EF:"
           " mutex="+hx(mutex)+", attr="+hx(attr)+
           ", policy="+std::to_string(protocol)+", recursive="+
           std::to_string(recursive==0x10)+", id="+hx(id);
      return true;
    }
    if (imp.nid==0x1573dc3fu || imp.nid==0xaeb78725u ||
        imp.nid==0x1bc200f4u || imp.nid==0xc3476d0cu) {
      // Single-threaded simulated mutex semantics for this narrow startup
      // tracer. Any operation needing blocking/multithreading stops explicitly.
      uint64_t mutex=c.r[3];
      auto it=std::find_if(m.mutexes.begin(),m.mutexes.end(),
                         [&](const Memory::LwMutex& st) {
                           return st.alive && st.guestAddr==mutex;
                         });
      if (it==m.mutexes.end() || !inrange(mutex,24,LIMIT)) {
        stop=std::string(known_import_name(imp))+
             ": mutex not registered or invalid "+hx(mutex);
        return false;
      }
      constexpr uint32_t thread_id=uint32_t(MAIN_PPU_THREAD_ID);
      if (imp.nid==0xc3476d0cu) {
        if (it->owner!=0xffffffffu) {
          stop="sys_lwmutex_destroy: mutex still locked "+hx(mutex);
          return false;
        }
        it->alive=false;
        if (!m.put(mutex,4,0xfffffffeu)) {
          stop="sys_lwmutex_destroy: failed guest write";return false;
        }
        c.r[3]=0;c.writes++;
      } else if (imp.nid==0x1bc200f4u) {
        if (it->owner!=thread_id) {
          stop="sys_lwmutex_unlock: mutex not owned by guest thread";
          return false;
        }
        if (it->recursion>0) --it->recursion;
        else it->owner=0xffffffffu;
        if (!m.put(mutex,4,it->owner) || !m.put(mutex+12,4,it->recursion)) {
          stop="sys_lwmutex_unlock: guest memory write failed";return false;
        }
        c.r[3]=0;c.writes+=2;
      } else {
        if (it->owner==0xffffffffu) {
          it->owner=thread_id;
        } else if(it->owner==thread_id && (it->attributes&0x10u)) {
          ++it->recursion;
        } else if (imp.nid==0xaeb78725u) {
          // CELL_EBUSY: do not fake acquiring an occupied mutex.
          c.r[3]=0x8001000au;c.pc=c.lr;
          event="[HLE] sys_lwmutex_trylock: mutex busy";
          return true;
        } else {
          stop="sys_lwmutex_lock: would block (requires LV2 scheduler)";
          return false;
        }
        if (!m.put(mutex,4,it->owner) || !m.put(mutex+12,4,it->recursion)) {
          stop="sys_lwmutex_lock: guest memory write failed";return false;
        }
        c.r[3]=0;c.writes+=2;
      }
      c.pc=c.lr;
      event="[HLE] "+std::string(known_import_name(imp))+
            ": mutex="+hx(mutex)+" returned "+hx(c.r[3]);
      return true;
    }
  }
  stop="Firmware HLE handler missing for "+imp.module+" "+
    known_import_name(imp)+" NID "+hx(imp.nid)+
    " (guest stub "+hx(imp.stub)+")"+
    "; guest args r3="+hx(c.r[3])+", r4="+hx(c.r[4])+
    ", r5="+hx(c.r[5])+", r6="+hx(c.r[6]);
  return false;
}

// PS3 LV2 time services. The 80 MHz timebase follows RPCS3's
// sys_time.cpp implementation (constant 80000000, no guest pointers).
// sys_time_get_current_time has two big-endian signed 64-bit out pointers.
constexpr uint64_t PS3_TIMEBASE_HZ=80000000ull;
bool lv2_time_service(uint64_t number, CPU& c, Memory& m,
                      std::string& event, std::string& stop) {
  if(number==147) { // sys_time_get_timebase_frequency()
    c.r[3]=PS3_TIMEBASE_HZ; // PPC64 ABI: 64-bit integer result in r3
    event="[LV2 HLE] sys_time_get_timebase_frequency (147): "+
          std::to_string(PS3_TIMEBASE_HZ)+" Hz";
    return true;
  }
  if(number==145) { // sys_time_get_current_time(s64* sec, s64* nsec)
    const uint64_t secAddr=c.r[3], nsecAddr=c.r[4];
    if(!secAddr || !nsecAddr || (secAddr&7) || (nsecAddr&7) ||
       !inrange(secAddr,8,LIMIT) || !inrange(nsecAddr,8,LIMIT)) {
      stop="sys_time_get_current_time: invalid 64-bit guest output pointer "
           "sec="+hx(secAddr)+" nsec="+hx(nsecAddr);
      return false;
    }
    // Real Unix wall-clock time rather than monotonic process uptime.
    const auto now=std::chrono::system_clock::now().time_since_epoch();
    const auto ns=std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
    int64_t sec=ns/1000000000ll;
    int64_t nsec=ns%1000000000ll;
    if(nsec<0){--sec;nsec+=1000000000ll;}
    if(!m.put(secAddr,8,uint64_t(sec)) || !m.put(nsecAddr,8,uint64_t(nsec))) {
      stop="sys_time_get_current_time: failed guest write";
      return false;
    }
    c.writes+=2;
    c.r[3]=0; // CELL_OK
    event="[LV2 HLE] sys_time_get_current_time (145): sec="+
          std::to_string(sec)+", nsec="+std::to_string(nsec);
    return true;
  }
  return false;
}

void crcmp(CPU& c,unsigned field,uint64_t a,uint64_t b,bool uns=false) {
  int v=uns?(a<b?8:a>b?4:2):(int64_t(a)<int64_t(b)?8:int64_t(a)>int64_t(b)?4:2);
  unsigned sh=28-((field&7)*4);c.cr=(c.cr&~(0xfu<<sh))|(uint32_t(v)<<sh);
}
bool crbit(const CPU& c,unsigned i) {return ((c.cr>>(31-(i&31)))&1)!=0;}

// Division X-form operations. Read sources before writing the result:
// actual BCES00220 startup uses divdu r27,r27,r3 (0x7F7B1B92).
// Reject undefined zero-divisor and signed-overflow cases rather than
// pretending the game produced a valid quotient.
bool ppu_divide(uint32_t ins,CPU& c,std::string& error) {
  if((ins>>26)!=31) return false;
  const unsigned xo=(ins>>1)&1023;
  if(xo!=457 && xo!=489 && xo!=459 && xo!=491) return false;
  const unsigned rt=(ins>>21)&31,ra=(ins>>16)&31,rb=(ins>>11)&31;
  const uint64_t dividend=c.r[ra],divisor=c.r[rb];
  if(divisor==0 || ((xo==459 || xo==491) && uint32_t(divisor)==0)) {
    error="Division by zero in PowerPC "+std::to_string(xo)+
          " at dividend="+hx(dividend)+", divisor="+hx(divisor);
    return false;
  }
  uint64_t quotient=0;
  switch(xo) {
    case 457: // divdu: unsigned 64-bit quotient
      quotient=dividend/divisor;
      break;
    case 489: { // divd: signed 64-bit quotient
      const int64_t a=int64_t(dividend),b=int64_t(divisor);
      if(a==INT64_MIN && b==-1) {
        error="Signed 64-bit divd overflow (INT64_MIN / -1)";return false;
      }
      quotient=uint64_t(a/b);break;
    }
    case 459: { // divwu: unsigned 32-bit quotient
      const uint32_t a=uint32_t(dividend),b=uint32_t(divisor);
      quotient=uint64_t(a/b);break;
    }
    case 491: { // divw: signed 32-bit quotient
      const int32_t a=int32_t(uint32_t(dividend));
      const int32_t b=int32_t(uint32_t(divisor));
      if(a==INT32_MIN && b==-1) {
        error="Signed 32-bit divw overflow (INT32_MIN / -1)";return false;
      }
      quotient=uint64_t(int64_t(a/b));break;
    }
  }
  c.r[rt]=quotient;
  // Record forms use CR0 and compare the result signed.
  if(ins&1) crcmp(c,0,quotient,0);
  return true;
}

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
  c.r[3]=1;c.r[7]=MAIN_PPU_THREAD_ID;c.r[8]=tlsVa;c.r[9]=tlsFileSize;c.r[10]=tlsMemSize;
  c.r[13]=0; // sys_initialize_tls must create the actual thread-local state.
  unsigned hleCalls=0, lv2Calls=0;
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
      // The PS3 import trampoline includes 'std r2,40(r1)' before replacing
      // the caller's TOC with the firmware module's own TOC. Intercepting the
      // stub before executing it MUST preserve this ABI side effect.
      // Without it, the guest's 'ld r2,40(r1)' restores zero and subsequent
      // r2-relative global loads fault (as observed at 0x45713C).
      if(!m.put(c.r[1]+40,8,c.r[2])) {
        stop="Import bridge could not save caller TOC at stack address "+
             hx(c.r[1]+40);
        break;
      }
      ++c.writes;
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
    uint64_t badAddress=0;int badBytes=0;
    const char* badOperation="";
    auto base=[&](unsigned x)->uint64_t{return x?c.r[x]:0;};
    auto load=[&](uint64_t addr,int bytes)->uint64_t {
      uint64_t v=0;
      if(!m.get(addr,bytes,v)) {
        memok=false;badAddress=addr;badBytes=bytes;badOperation="load";
      } else c.reads++;
      return v;
    };
    auto store=[&](uint64_t addr,int bytes,uint64_t v){
      if(!m.put(addr,bytes,v)) {
        memok=false;badAddress=addr;badBytes=bytes;badOperation="store";
      } else c.writes++;
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
      // PowerPC X-form single-register logical operations.
      // The source is RS (rt field) and destination RA (ra field).
      // EXTSh appears at BCES00220 guest PC 0x44F0F4, then again at
      // 0x44F0FC / 0x44F100. Preserve sign extension to full 64 bits.
      case 954: c.r[ra]=uint64_t(int64_t(int8_t(c.r[rt]))); break;    // extsb
      case 922: c.r[ra]=uint64_t(int64_t(int16_t(c.r[rt]))); break;   // extsh
      case 986: c.r[ra]=uint64_t(int64_t(int32_t(c.r[rt]))); break;   // extsw
      case 26: {uint32_t v=uint32_t(c.r[rt]);c.r[ra]=v?unsigned(__builtin_clz(v)):32u;break;} // cntlzw
      case 58: {uint64_t v=c.r[rt];c.r[ra]=v?unsigned(__builtin_clzll(v)):64u;break;} // cntlzd
            case 444:c.r[ra]=c.r[rt]|c.r[rb];break;
      case 28:c.r[ra]=c.r[rt]&c.r[rb];break;
      case 316:c.r[ra]=c.r[rt]^c.r[rb];break;
      case 266:c.r[rt]=c.r[ra]+c.r[rb];break;
      case 457:case 489:case 459:case 491: {
        std::string div_error;
        if(!ppu_divide(ins,c,div_error)) {
          stop=div_error+" at guest PC "+hx(here);
          ok=false;
        }
        break;
      }
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
      // A set Rc bit on these X-form operations updates CR0 with signed
      // result comparison, rather than leaving stale branch flags.
      if(ok && (ins&1) &&
         (xo==954 || xo==922 || xo==986 || xo==26 || xo==58))
        crcmp(c,0,c.r[ra],0);
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
    case 17: {
      // PS3 LV2 syscall is selected in r11 (unlike Linux on PowerPC).
      // RPCS3/ps3recomp: 352 = sys_memory_get_user_memory_size(info*),
      // struct {be32 total_user_memory; be32 available_user_memory}.
      if(c.r[11]==352) {
        const uint64_t info=c.r[3];
        if(!inrange(info,8,LIMIT)) {
          stop="sys_memory_get_user_memory_size: invalid guest result pointer "+hx(info);
          ok=false;
          break;
        }
        constexpr uint32_t modeledTotal=0x10000000u; // PS3 256 MiB main memory
        constexpr uint32_t modeledAvailable=0x0E000000u; // 224 MiB simulated free
        if(!m.put(info,4,modeledTotal)||!m.put(info+4,4,modeledAvailable)){
          stop="sys_memory_get_user_memory_size: could not write 8-byte result at "+hx(info);
          ok=false;break;
        }
        c.r[3]=0;c.writes+=2;lv2Calls++;
        if(lv2Calls<8)
          o<<"[LV2 HLE] sys_memory_get_user_memory_size (352): modeled PS3 user memory total="
           <<hx(modeledTotal)<<", available="<<hx(modeledAvailable)
           <<", destination="<<hx(info)<<"\n";
      } else if(c.r[11]==147 || c.r[11]==145) {
        std::string event;
        if(!lv2_time_service(c.r[11],c,m,event,stop)) {
          ok=false;break;
        }
        ++lv2Calls;
        if(lv2Calls<16) o<<event<<"\n";
      } else {
        stop="PS3 LV2 syscall "+std::to_string(c.r[11])+
          " not implemented at guest PC "+hx(here)+
          "; r3="+hx(c.r[3])+", r4="+hx(c.r[4]);
        ok=false;
      }
      break;
    }
    default:ok=false;break;
    }
    if(step<48)o<<"["<<step<<"] "<<hx(here)<<" "<<hx(ins)<<(ok?" executed":" unsupported")<<"\n";
    if(!ok) {
      if(stop.empty()) {
        stop="Unsupported PPC instruction: opcode "+std::to_string(op);
        if(op==31 || op==19)
          stop+=" XO="+std::to_string((ins>>1)&1023);
        stop+=" instruction="+hx(ins)+" at guest PC "+hx(here);
      }
      break;
    }
    if(!memok){
      stop="Guest "+std::string(badOperation)+" out of bounds at "+hx(badAddress)+
           " ("+std::to_string(badBytes)+" bytes), instruction PC="+hx(here)+
           ", opcode="+hx(ins)+", r2/TOC="+hx(c.r[2])+
           ", r1/stack="+hx(c.r[1]);
      break;
    }
    c.pc=next;c.instructions++;
  }
  if(stop.empty())stop="Instruction budget reached";
  o<<"\nGuest firmware HLE calls handled: "<<hleCalls<<"\n"
   <<"LV2 syscalls handled: "<<lv2Calls<<"\n"
   <<"Real PPU instructions executed: "<<c.instructions<<"\n"
   <<"Branches: "<<c.branches<<"; linked calls: "<<c.calls<<"\n"
   <<"Guest memory reads: "<<c.reads<<"; writes: "<<c.writes<<"\n"
   <<"Final guest PC: "<<hx(c.pc)<<"\n"
   <<"Final r3: "<<hx(c.r[3])<<"\n"
   <<"Final r2/TOC: "<<hx(c.r[2])<<"\n"
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
int test_time_hle() {
  // Self-contained genuine-HLE regression without any private PS3 game data.
  // Check the PowerPC ABI: 64-bit microsecond result in r3, LR -> PC return,
  // no guest memory mutation, and clock progress between successive calls.
  realppu::Memory memory;
  realppu::CPU cpu;
  cpu.lr=0x10230;
  cpu.pc=0x55d96c;
  cpu.r[3]=0x10000;
  realppu::Import clockImport{0x55d96c, 0x8461e528u, "sysPrxForUser"};
  std::string event, stop;
  if (!realppu::import_hle(clockImport,cpu,memory,event,stop)) {
    std::fprintf(stderr,"time HLE failed: %s\n",stop.c_str()); return 1;
  }
  const uint64_t first=cpu.r[3];
  if (first==0 || cpu.pc!=cpu.lr || event.find("monotonic clock")==std::string::npos) {
    std::fputs("time HLE returned invalid r3, PC, or event\n", stderr); return 1;
  }
  std::this_thread::sleep_for(std::chrono::milliseconds(3));
  cpu.pc=clockImport.stub;
  if (!realppu::import_hle(clockImport,cpu,memory,event,stop) ||
      cpu.r[3] <= first || cpu.pc != cpu.lr) {
    std::fputs("time HLE did not advance and return through LR\n",stderr); return 1;
  }
  std::printf("TIME_HLE_REGRESSION_PASS: timer advanced %llu usec\n",
    (unsigned long long)(cpu.r[3]-first));
  return 0;
}
int test_lv2_time_services() {
  realppu::Memory m;
  realppu::CPU c;
  const uint64_t savedWrites=c.writes;
  const uint64_t prevR4=0xFFFF;
  c.r[3]=0x9edcc0;
  c.r[4]=prevR4;
  std::string event, stop;
  if(!realppu::lv2_time_service(147,c,m,event,stop) ||
     c.r[3]!=realppu::PS3_TIMEBASE_HZ || c.r[4]!=prevR4 ||
     c.writes!=savedWrites || event.find("80000000 Hz")==std::string::npos) {
    std::fprintf(stderr,"timebase frequency syscall failed: %s\n",stop.c_str());
    return 1;
  }
  // Current time: verify both 8-byte big-endian PS3 guest outputs are
  // populated, nanoseconds stay within [0, 1e9), and r3 reports CELL_OK.
  c.r[3]=0x10000;
  c.r[4]=0x10008;
  if(!realppu::lv2_time_service(145,c,m,event,stop) ||
     c.r[3]!=0 || c.writes!=2) {
    std::fprintf(stderr,"current time syscall failed: %s\n",stop.c_str());
    return 1;
  }
  uint64_t sec=0,nsec=0;
  if(!m.get(0x10000,8,sec)||!m.get(0x10008,8,nsec) ||
     sec<1700000000ULL || nsec>=1000000000ULL) {
    std::fputs("current time guest memory format invalid\n",stderr);
    return 1;
  }
  c.r[3]=realppu::LIMIT-4;
  c.r[4]=0x10008;
  if(realppu::lv2_time_service(145,c,m,event,stop) ||
     stop.find("invalid 64-bit guest output pointer")==std::string::npos) {
    std::fputs("time syscall must reject invalid guest pointers\n",stderr);
    return 1;
  }
  std::printf("LV2_TIME_SERVICES_PASS: 147=%llu Hz, 145 returned sec=%llu/nsec=%llu\n",
    (unsigned long long)realppu::PS3_TIMEBASE_HZ,
    (unsigned long long)sec,(unsigned long long)nsec);
  return 0;
}
int test_division_ops() {
  std::string err;
  realppu::CPU c;
  // Exact instruction from user's European inFAMOUS trace:
  // 0x7F7B1B92 == divdu r27,r27,r3, 64-bit unsigned division.
  c.r[27]=100000000000ULL;
  c.r[3]=125ULL;
  if(!realppu::ppu_divide(0x7F7B1B92u,c,err) ||
     c.r[27]!=800000000ULL) {
    std::fprintf(stderr,"BCES00220 divdu failed: %s\n",err.c_str());return 1;
  }
  // Same instruction with Rc=1: quotient updates CR0, preserve XER.
  c.r[27]=128;c.r[3]=8;c.xer=0x60000000u;c.cr=0;
  if(!realppu::ppu_divide(0x7F7B1B93u,c,err) || c.r[27]!=16 ||
     (c.cr>>28)!=4 || c.xer!=0x60000000u) {
    std::fputs("divdu. CR0 result / XER preservation failed\n",stderr);return 1;
  }
  c.r[27]=123;c.r[3]=0;err.clear();
  if(realppu::ppu_divide(0x7F7B1B92u,c,err) ||
     err.find("Division by zero")==std::string::npos ||
     c.r[27]!=123) {
    std::fputs("divdu zero-divisor should stop without writing\n",stderr);return 1;
  }
  // Signed 64-bit division, divd r27,r27,r3.
  const uint32_t divd=(0x7F7B1B92u&~(1023u<<1))|(489u<<1);
  c.r[27]=uint64_t(-144ll);c.r[3]=12;err.clear();
  if(!realppu::ppu_divide(divd,c,err) || int64_t(c.r[27])!=-12) {
    std::fputs("divd signed quotient incorrect\n",stderr);return 1;
  }
  // Unsigned 32-bit division rejects a zero LOW word even when high
  // bits of the original 64-bit register are nonzero.
  const uint32_t divwu=(0x7F7B1B92u&~(1023u<<1))|(459u<<1);
  c.r[27]=0xFFFFFFFFu;c.r[3]=0x100000000ULL;err.clear();
  if(realppu::ppu_divide(divwu,c,err) ||
     err.find("Division by zero")==std::string::npos) {
    std::fputs("divwu low-word zero divisor check failed\n",stderr);return 1;
  }
  c.r[27]=0xFFFFFFFEu;c.r[3]=2;err.clear();
  if(!realppu::ppu_divide(divwu,c,err) || c.r[27]!=0x7FFFFFFFULL) {
    std::fputs("divwu 32-bit result incorrect\n",stderr);return 1;
  }
  // Signed 32-bit word division sign extends the quotient.
  const uint32_t divw=(0x7F7B1B92u&~(1023u<<1))|(491u<<1);
  c.r[27]=0xFFFFFFECu;c.r[3]=4;err.clear();
  if(!realppu::ppu_divide(divw,c,err) || int64_t(c.r[27])!=-5) {
    std::fputs("divw signed word quotient incorrect\n",stderr);return 1;
  }
  c.r[27]=uint64_t(INT64_MIN);c.r[3]=uint64_t(-1ll);err.clear();
  if(realppu::ppu_divide(divd,c,err) ||
     err.find("overflow")==std::string::npos) {
    std::fputs("divd signed-overflow check failed\n",stderr);return 1;
  }
  std::puts("PPC_DIVISION_REGRESSION_PASS: actual BCES00220 divdu and 32/64-bit variants");
  return 0;
}
int test_thread_get_id() {
  realppu::Memory m;
  realppu::CPU c;
  c.pc=0x55d8ec;
  c.lr=0x447c40;
  c.r[3]=0x2effaf0; // Actual pointer reported from BCES00220 startup.
  const realppu::Import imp{0x55d8ec,0x350d454eu,"sysPrxForUser"};
  std::string event, stop;
  if(!realppu::import_hle(imp,c,m,event,stop)) {
    std::fprintf(stderr,"sys_ppu_thread_get_id HLE failed: %s\n",stop.c_str());
    return 1;
  }
  uint64_t value=0;
  if(!m.get(0x2effaf0,8,value) ||
     value!=realppu::MAIN_PPU_THREAD_ID ||
     c.r[3]!=0 || c.pc!=c.lr || c.writes!=1 ||
     event.find("wrote 64-bit thread_id")==std::string::npos) {
    std::fputs("sys_ppu_thread_get_id wrong ABI / guest write / LR return\n",stderr);
    return 1;
  }
  const uint8_t expected[8]={0,0,0,0,1,0,0,0};
  if(std::memcmp(m.image.data()+0x2effaf0,expected,8)) {
    std::fputs("sys_ppu_thread_get_id not big-endian 64-bit\n",stderr);
    return 1;
  }
  // Avoid silent writes to null, unaligned, or unmapped pointers.
  for(uint64_t addr : {0ull,0x2effaf1ull,realppu::LIMIT-4}) {
    c.r[3]=addr;
    c.pc=imp.stub;
    stop.clear();
    if(realppu::import_hle(imp,c,m,event,stop) ||
       stop.find("invalid 64-bit guest output pointer")==std::string::npos ||
       c.pc!=imp.stub || c.writes!=1) {
      std::fputs("sys_ppu_thread_get_id must fail closed on invalid pointer\n",stderr);
      return 1;
    }
  }
  std::puts("PPU_THREAD_ID_REGRESSION_PASS: 64-bit BE guest store and return");
  return 0;
}
int main(int argc,char** argv){
  if (argc==2 && std::string(argv[1])=="--test-time-hle")
    return test_time_hle();
  if (argc==2 && std::string(argv[1])=="--test-lv2-time")
    return test_lv2_time_services();
  if(argc==2 && std::string(argv[1])=="--test-division")
    return test_division_ops();
  if(argc==2 && std::string(argv[1])=="--test-thread-id")
    return test_thread_get_id();
  if(argc!=2)return 2;
  std::ifstream in(argv[1],std::ios::binary);
  if(!in)return 2;
  std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)),{});
  std::puts(realppu::run(bytes.data(),bytes.size()).c_str());
  return 0;
}
#endif
