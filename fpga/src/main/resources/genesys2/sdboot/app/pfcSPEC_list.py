import re
import csv
import sys
import copy
import pandas as pd
from scipy.stats.mstats import gmean

case_name = [
  "400.perlbench"  ,##0
  "401.bzip2"      ,##1
  "403.gcc"        ,##2
  "410.bwaves"     ,##3
  "416.gamess"     ,##4
  "429.mcf"        ,##5
  "433.milc"       ,##6
  "434.zeusmp"     ,##7
  "435.gromacs"    ,##8
  "436.cactusADM"  ,##9
  "437.leslie3d"   ,##10
  "444.namd"       ,##11
  "445.gobmk"      ,##12
  "447.dealII"     ,##13  
  "450.soplex"     ,##14
  "453.povray"     ,##15
  "454.calculix"   ,##16
  "456.hmmer"      ,##17
  "458.sjeng"      ,##18
  "459.GemsFDTD"   ,##19
  "462.libquantum" ,##20
  "464.h264ref"    ,##21
  "465.tonto"      ,##22
  "470.lbm"        ,##23
  "471.omnetpp"    ,##24
  "473.astar"      ,##25
  "481.wrf"        ,##26
  "482.sphinx3"    ,##27
  "483.xalancbmk"   ##28
]

coreEvent = [
  "cycle                 ", ##event0
  "instruction           ", ##event1
  "exception             ", ##event2
  "load                  ", ##event3
  "store                 ", ##event4
  "amo                   ", ##event5
  "system                ", ##event6
  "arith                 ", ##event7
  "branch                ", ##event8
  "jal                   ", ##event9
  "jalr                  ", ##event10
  ##(usingMulDiv)
  "mul                   ", ##event11
  "div                   ", ##event12
  ##(usingFPU)
  "fp_load               ", ##event13
  "fp_store              ", ##event14
  "fp_add                ", ##event15
  "fp_mul                ", ##event16
  "fp_muladd             ", ##event17
  "fp_divsqrt            ", ##event18
  "fp_other              ", ##event19
  "load_use_interlock    ", ##event20
  "long_latency_interlock", ##event21
  "csr_interlock         ", ##event22
  "Iblocked              ", ##event23
  "Dblocked              ", ##event24
  "branch_misprediction  ", ##event25
  "cft_misprediction     ", ##event26: controlflow_target_misprediction
  "flush                 ", ##event27
  "replay                ", ##event28
  ##(usingMulDiv)
  "muldiv_interlock      ", ##event29
  ##(usingFPU)
  "fp_interlock          ", ##event30
  "Imiss                 ", ##event31
  "Dmiss                 ", ##event32
  "Drelease              ", ##event33
  "ITLBmiss              ", ##event34
  "DTLBmiss              ", ##event35
  "L2TLBmiss             "  ##event36
]

TLEvent = [
  ##a: Acquire channel
  "a_Done              ", ##event0
  "a_PutFullData       ", ##event1
  "a_PutPartialData    ", ##event2
  "a_ArithmeticData    ", ##event3
  "a_LogicalData       ", ##event4
  "a_Get               ", ##event5
  "a_Hint              ", ##event6
  "a_AcquireBlock      ", ##event7
  "a_AcquirePerm       ", ##event8
  "a_Blocked           ", ##event9
  "a_Err0              ", ##event10
  "a_Err1              ", ##event11
  ##b: Probe channel
  "b_Done              ", ##event12
  "b_PutFullData       ", ##event13
  "b_PutPartialData    ", ##event14
  "b_ArithmeticData    ", ##event15
  "b_LogicalData       ", ##event16
  "b_Get               ", ##event17
  "b_Hint              ", ##event18
  "b_Probe             ", ##event19
  "b_Blocked           ", ##event20
  "b_Err0              ", ##event21
  "b_Err1              ", ##event22
  ##c: Release channel
  "c_Done              ", ##event23
  "c_AccessAck         ", ##event24
  "c_AccessAckData     ", ##event25
  "c_HintAck           ", ##event26
  "c_ProbeAck          ", ##event27
  "c_ProbeAckData      ", ##event28
  "c_Release           ", ##event29
  "c_ReleaseData       ", ##event30
  "c_Blocked           ", ##event31
  "c_Err0              ", ##event32
  "c_Err1              ", ##event33
  ##d: Grant channel
  "d_Done              ", ##event34
  "d_AccessAck         ", ##event35
  "d_AccessAckData     ", ##event36
  "d_HintAck           ", ##event37
  "d_Grant             ", ##event38
  "d_GrantData         ", ##event39
  "d_ReleaseAck        ", ##event40
  "d_Blocked           ", ##event41
  "d_Err0              ", ##event42
  "d_Err1              ", ##event43
  ##e: Finish channel
  "e_Done              ", ##event44
  "e_GrantAck          ", ##event45
  "e_Blocked           ", ##event46
  "e_Err0              ", ##event47
  "e_Err1              "  ##event48
]

RMPEvent = [
  "finish              ", ##event0
  "nop                 ", ##event1
  "busy                ", ##event2
  "swap                ", ##event3
  "evict               ", ##event4
  "ebusy               ", ##event5
  "pause               ", ##event6
  "atdetec             "  ##event7
]

def pfcid2caseid(pfc_id):
  ##INT
  if(pfc_id <=  3):    return 0   ##400.perlbench
  elif(pfc_id <=  9):  return 1   ##401.bzip2 
  elif(pfc_id <= 18):  return 2   ##403.gcc
  elif(pfc_id <= 23):  return 12  ##445.gobmk
  elif(pfc_id <= 24):  return 5   ##429.mcf
  elif(pfc_id <= 26):  return 17  ##456.hmmer
  elif(pfc_id <= 27):  return 18  ##458.sjeng
  elif(pfc_id <= 28):  return 20  ##462.libquantum
  elif(pfc_id <= 31):  return 21  ##464.h264ref
  elif(pfc_id <= 32):  return 24  ##471.omnetpp
  elif(pfc_id <= 34):  return 25  ##473.astar
  elif(pfc_id <= 35):  return 28  ##483.xalancbmk
  ##FP
  elif(pfc_id <= 36):  return 3   ##410.bwaves
  elif(pfc_id <= 39):  return 4   ##416.gamess
  elif(pfc_id <= 40):  return 6   ##433.milc
  elif(pfc_id <= 41):  return 7   ##434.zeusmp
  elif(pfc_id <= 42):  return 8   ##435.gromacs
  elif(pfc_id <= 43):  return 9   ##436.cactusADM
  elif(pfc_id <= 44):  return 10  ##437.leslie3d
  elif(pfc_id <= 45):  return 11  ##444.namd
  elif(pfc_id <= 46):  return 13  ##447.dealII
  elif(pfc_id <= 48):  return 14  ##450.soplex
  elif(pfc_id <= 49):  return 15  ##453.povray
  elif(pfc_id <= 50):  return 16  ##454.calculix
  elif(pfc_id <= 51):  return 19  ##459.GemsFDTD
  elif(pfc_id <= 52):  return 22  ##465.tonto
  elif(pfc_id <= 53):  return 23  ##470.lbm
  elif(pfc_id <= 54):  return 26  ##481.wrf
  elif(pfc_id <= 55):  return 27  ##482.sphinx3


class PFC:
  pass


cases = 28
##case=[PFC()]*30 ##need copy.deepcopy
pfc     = []
configs = []
for i in range(0, len(sys.argv)-1): configs += [sys.argv[i+1]]
##read pfcfile  
for config_id in range(0, len(configs)):
  pfc += [[PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(),
           PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(),
           PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(),
           PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(),
           PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(), PFC(),
           PFC(), PFC(), PFC(), PFC(), PFC()]]
  for pfc_id in range(0, len(pfc[0])):
    pfc[config_id][pfc_id].runtime    = 0
    pfc[config_id][pfc_id].cmd        = []
    pfc[config_id][pfc_id].core       = [[0]*37]*32
    pfc[config_id][pfc_id].core_anl   = [[[" ", 0.0]]*5]*32    ##each core analyse: ipc impki dmpki 
    pfc[config_id][pfc_id].acore      = [0]*37
    pfc[config_id][pfc_id].l2itl      = [0]*49
    pfc[config_id][pfc_id].l2otl      = [0]*49
    pfc[config_id][pfc_id].l2rmp      = [0]*8
    pfc[config_id][pfc_id].analyse    = []                     ##analyse: ipc impki dmpki itlbmpki dtlbmpki l2tlbmpki l2mpki l2rpgi 
  c_pfc   = [0]*64
  core_id = 0
  coreevent_id  =0
  l2iTLevent_id =0
  l2oTLevent_id =0
  rmpevent_id =0
  file_object = open(sys.argv[config_id+1], 'r')
  for line in file_object:
    if '.\\' in line: break         ##break cmd
    if line==''     : break         ##break empty line
    if '------pfc_' in line:
      core_id        = 0
      coreevent_id   = 0
      l2iTLevent_id  = 0
      l2oTLevent_id  = 0
      l2RMPevent_id  = 0
      pfc_id  = int("".join(re.findall("\d+", line)))
    if 'run_time' in line:
      pfc[config_id][pfc_id].runtime  += int("".join(line.split(": ")[1]))
    if './' in line:
      pfc[config_id][pfc_id].cmd      += [line.strip('\n')]
    if 'CORE' in line:
      o_pfc = int("".join(line.split(": ")[1]))   ##one pfc
      c_pfc[coreevent_id]                         = o_pfc
      if coreevent_id != 0:
        pfc[config_id][pfc_id].acore[coreevent_id]         += o_pfc
        pfc[config_id][pfc_id].core[core_id][coreevent_id] += o_pfc
      elif core_id == 0:                                   ##do not record multiple cycle
        pfc[config_id][pfc_id].acore[coreevent_id]         += o_pfc ##cycle
        pfc[config_id][pfc_id].core[core_id][coreevent_id] += o_pfc ##cycle
      coreevent_id += 1
      if '_L2TLBmiss' in line:        ##core last event
        c_ipc= (float)(c_pfc[1]) / (float)(c_pfc[0])
        if c_ipc < 0.1 : print('warning ipc %6f below 0.1 pfc_id %d' %(c_ipc, pfc_id))
        core_id     += 1
        coreevent_id = 0
    if 'ILINK' in line:
      pfc[config_id][pfc_id].l2itl[l2iTLevent_id] += int("".join(line.split(": ")[1]))
      l2iTLevent_id +=1
    if 'OLINK' in line:
      pfc[config_id][pfc_id].l2otl[l2oTLevent_id] += int("".join(line.split(": ")[1]))
      l2oTLevent_id +=1
    if 'RMPER' in line:
      pfc[config_id][pfc_id].l2rmp[l2RMPevent_id] += int("".join(line.split(": ")[1]))
      l2RMPevent_id +=1


def div(a, b):
  if b == 0: return 0
  else     : return a/b
##analyse
for config_id in range(0, len(configs)):
  for pfc_id in range(0, len(pfc[0])):
    pfc[config_id][pfc_id].analyse  += [["ipc",         div(float(pfc[config_id][pfc_id].acore[1]),                   pfc[config_id][pfc_id].acore[0])]]
    pfc[config_id][pfc_id].analyse  += [["cpi",         div(float(pfc[config_id][pfc_id].acore[0]),                   pfc[config_id][pfc_id].acore[1])]]
    pfc[config_id][pfc_id].analyse  += [["impki",       div(float(1000         * pfc[config_id][pfc_id].acore[31]),   pfc[config_id][pfc_id].acore[1])]]
    pfc[config_id][pfc_id].analyse  += [["dmpki",       div(float(1000         * pfc[config_id][pfc_id].acore[32]),   pfc[config_id][pfc_id].acore[1])]]
    pfc[config_id][pfc_id].analyse  += [["itlbmpki",    div(float(1000         * pfc[config_id][pfc_id].acore[34]),   pfc[config_id][pfc_id].acore[1])]]
    pfc[config_id][pfc_id].analyse  += [["dtlbmpki",    div(float(1000         * pfc[config_id][pfc_id].acore[35]),   pfc[config_id][pfc_id].acore[1])]]
    pfc[config_id][pfc_id].analyse  += [["l2tlbmpki",   div(float(1000         * pfc[config_id][pfc_id].acore[36]),   pfc[config_id][pfc_id].acore[1])]]
    pfc[config_id][pfc_id].analyse  += [["l2apki",      div(float(1000         * pfc[config_id][pfc_id].l2itl[0]),    pfc[config_id][pfc_id].acore[1])]]
    pfc[config_id][pfc_id].analyse  += [["l2mpki",      div(float(1000         * pfc[config_id][pfc_id].l2otl[0]),    pfc[config_id][pfc_id].acore[1])]]
    pfc[config_id][pfc_id].analyse  += [["l2mrate",     div(float(               pfc[config_id][pfc_id].l2otl[0]),    pfc[config_id][pfc_id].l2itl[0])]]
    pfc[config_id][pfc_id].analyse  += [["l2rmpgi",     div(float(pow(1000, 3) * pfc[config_id][pfc_id].l2rmp[0]),    pfc[config_id][pfc_id].acore[1])]]

for config_id in range(0, len(configs)):
  with open('./tmp/'+configs[config_id]+'list.csv', "w", newline='') as datacsv:
    csvwriter = csv.writer(datacsv)
    title  = ['case']
    for pfc_id in range(0, len(pfc[0])):
      if pfc[config_id][pfc_id].analyse[0][0] != ' ':
        title  += ['cmd'] + ['runtime'] + [j[0] for j in pfc[config_id][pfc_id].analyse]
        break
    title += ['Core_'+ x.replace(' ', '') for x in coreEvent] +['RMP_'+ x.replace(' ', '') for x in RMPEvent] +['L2ITL_'+ x.replace(' ', '') for x in TLEvent] + ['L2OTL_'+ x.replace(' ', '') for x in TLEvent]
    csvwriter.writerow(title)
    for pfc_id in range(0, len(pfc[0])):
      if pfc[config_id][pfc_id].acore[0] != 0 :
        ##pfc = c.runtime
        case_id = pfcid2caseid(pfc_id)
        pfcline = pfc[config_id][pfc_id].cmd + [pfc[config_id][pfc_id].runtime] + [j[1] for j in pfc[config_id][pfc_id].analyse] + pfc[config_id][pfc_id].acore + pfc[config_id][pfc_id].l2rmp + pfc[config_id][pfc_id].l2itl + pfc[config_id][pfc_id].l2otl
        csvwriter.writerow([case_name[case_id]] + pfcline)

