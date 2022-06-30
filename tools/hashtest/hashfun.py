import random
import hashlib
import math
import zlib
import numpy as np

##input 32 output 32

def md5(a):
    md5str = hashlib.md5(a.to_bytes(4, byteorder="little")).hexdigest()
    md5int32 = int(md5str[0 : 8], 16)
    return md5int32

def sha512(a):
    sha512str = hashlib.sha512(a.to_bytes(4, byteorder="little")).hexdigest()
    sha512int32 = int(sha512str[0 : 8], 16)
    return sha512int32

def crc32(a):
    crc32int32 = zlib.crc32(a.to_bytes(4, byteorder="little"))
    return(crc32int32)

##Hua2011 Non-Crypto Hardware Hash Functions for High Performance Networking ASICs
##t: 3x3 S-box stage (t for three)
##x: xor stage
##tx3t: one S-Box stage, three XOR stages, permutation block, and another S-box stage

def bitssel(a, hi, lo):
    result = a & (~(-1 << (hi + 1)))
    result = result >> lo
    return result

def xorreduce(a, iwidth, owidth):
    aa = a & (~(-1 << iwidth))
    mask = ~(-1 << owidth)
    result = aa & mask
    sections = int(iwidth/owidth)
    for i in range(1, sections):
        result = result ^ ((aa >> (i * owidth)) & mask)
    if sections < (iwidth/owidth):
        result = result ^ ((aa >> ((sections + 1) * owidth)) & mask)
    return result

def int2bitarray(a, width):
    bits  = []
    for i in range(0, width):
        bit   = 1
        temp  = a >> i
        if (temp % 2) == 0:
            bit = 0
        bits.append(bit)
    return bits

def bitarray2int(a):
    intnumber = 0
    width = len(a)
    for i in range(width - 1, -1, -1):
        assert a[i] < 2
        intnumber = (intnumber << 1) + a[i]
    return intnumber

def xorstage(a, width, stages):
    assert stages >= 1
    result    = a
    midresult = 0
    for i in range(0, stages):
        midresult = bitssel(result, 0, 0) ^ bitssel(result, 2, 2) ^ bitssel(result, width - 1, width - 1)
        for j in range(1, width): midresult = midresult + ((bitssel(result, j-1, j-1) ^ bitssel(result, j, j)) << j)
        result = midresult
    return result

def sbox3x3(a):
    ina      = bitssel(a, 2, 2)
    inb      = bitssel(a, 1, 1)
    inc      = bitssel(a, 0, 0)
    notina   = 0
    notinb   = 0
    notinc   = 0
    if ina  == 0: notina = 1
    if inb  == 0: notinb = 1
    if inc  == 0: notinc = 1
    Qa   = (notina  &    inb) | (notina  &    inc) | (   inb &    inc) ##Qa
    Qb   = (   ina  &    inb) | (   ina  & notinc) | (   inb & notinc) ##Qb
    Qc   = (notina  &    inb) | (notina  & notinc) | (   inb & notinc) ##Qc
    Qabc = (Qa << 2) + (Qb << 1) + Qc
    return Qabc

def sboxstage(a, width):
    result = 0
    j      = 0
    for i in range(0, width, 3):
        lo = i
        hi = i + 2
        hi = min(width - 1, i + 2)
        ##if hi >= width:
        ##    lo = width - 3
        ##    hi = width - 1       
        result = result + (sbox3x3(bitssel(a, hi, lo)) << i) ##include hi lo
    result = result & (~(-1 << width))
    return result

permutationlist6L = []
permutationlistRL = []

def permutationlist6Lgenerator():
    a      = []
    result = []
    for i in range(0, 32): a.append(i)
    random.shuffle(a)
    b = np.array_split(a, 3)
    random.shuffle(a)
    c = np.array_split(a, 3)
    for i in range(0, 3):
        b[i].sort()
        c[i].sort()
        cutbit = random.randint(3, int(len(b[i]) - 2))
        b[i] = b[i][cutbit: len(b[i])].tolist() + b[i][0: cutbit].tolist()
        c[i] = c[i].tolist()
    aa = [] 
    bb = []
    cc = []
    for i in range(0, 3):
        bb = bb + b[i]
        cc = cc + c[i]
    for i in range(0, 32):
        aa.append(0)
    for i in range(0, 32):
        aa[bb[i]] = cc[i]
    return aa


def permutationlist6Lgenerator2():
    result      = []
    width       = 32
    span        = int(width/3)
    for i in range(0, width): result.append(0)
    remainder   = width % 3
    p0          = [0         ,                 span]   ##[)
    p1          = [span      ,             2 * span]
    p2          = [2 * span  ,            width - 1]
    if remainder == 1:
        result[width - 1] = p2[0] 
    if remainder == 2: 
        result[width - 2] = p2[0] 
        result[width - 1] = p2[0] + 1
    p0sel       = 0
    p1sel       = span
    p2sel       = 2 * span + remainder
    for i in range(0, width - remainder):
        if i < p0[1] :
            if (i % 2) == 0:
                result[i] = p1sel
                p1sel     = p1sel + 1
            else           :
                result[i] = p2sel
                p2sel     = p2sel + 1
        elif i < p1[1] :
            if (i % 2) == 0:
                result[i] = p0sel
                p0sel     = p0sel + 1
            else           :
                result[i] = p2sel
                p2sel     = p2sel + 1
        elif i < p2[1] :
            if (i % 2) == 0:
                result[i] = p0sel
                p0sel     = p0sel + 1
            else           :
                result[i] = p1sel
                p1sel     = p1sel + 1
    return result

for i in range(0, 32):
    permutationlist6L.append([])
    permutationlistRL.append([])
    permutationlist6L[i] = permutationlist6Lgenerator2()
    for j in range(0, 32):
        permutationlistRL[i].append(j)
    random.shuffle(permutationlistRL[i])

def permutationstage(a, width, p):
    result = 0
    for i in range(0, width):
        result = result + (bitssel(a, p[i], p[i]) << i)
    return result

def tx3(a, stages, perlist):
    width       = 32
    result      = permutationstage(a, width, perlist[0])
    for i in range(0, stages): 
        result = permutationstage(sboxstage(result, width),    width, perlist[i])
        result = permutationstage(xorstage(result,  width, 1), width, perlist[i])
        result = permutationstage(xorstage(result,  width, 1), width, perlist[i])
        result = permutationstage(xorstage(result,  width, 1), width, perlist[i])
    return result

def tx3tx3tx3t(a):
    width = 32
    baidu = [16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10, 2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25]
    AA = [permutationlist6L[0]] + [permutationlist6L[0]] + [permutationlist6L[0]] + [permutationlist6L[0]] +[permutationlist6L[0]]
    result = tx3(a, 1,AA)
    result = sboxstage(result, width)
    ##result = tx3t(a, 3, aa)
    return result


def tx3nt(a, stages):
    width = 32
    baidu = [16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10, 2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25]
    AA = [permutationlist6L[0]] + [permutationlist6L[0]] + [permutationlist6L[0]] + [permutationlist6L[0]] +[permutationlist6L[0]]
    result = tx3(a, stages, AA)
    result = sboxstage(result, width)
    ##result = tx3t(a, 3, aa)
    return result

##print(permutationlist6L[0])
##print(sboxstage(0, 2))
##print(xorstage(1, 32, 4))
##exit()

##Maurice2015 Reverse Engineering Intel Last-Level Cache Complex Addressing Using Performance Counters

Maurice2015Width   = 6
Maurice2015Len     = (1 << Maurice2015Width)
Maurice2015Hkeys   = []
Maurice2015Rtable  = []

for i in range(0, Maurice2015Width):
    Maurice2015Hkeys.append(random.randint(0, int((1 << (32 - 6)) - 1)))

for i in range(0, Maurice2015Len):
    Maurice2015Rtable.append(i)

random.shuffle(Maurice2015Rtable)

def Maurice2015Index(a, hkeys):
    result = 0
    temp   = 0
    for i in range(0, len(hkeys)):
        temp0   = a & hkeys[i]
        temp1   = 0
        while temp0 > 0:
            temp1 = int(temp0 % 2) ^ temp1
            temp0 = temp0 >> 1
        assert((temp1 == 1) | (temp1 == 0))
        result  = result + (temp1 << i)
    return result

def Maurice2015Hua2011(a):
    index     = Maurice2015Index(a, Maurice2015Hkeys)
    rtableOut = Maurice2015Rtable[index]
    ##amix      = (rtableOut << 26) | a
    amix      = (rtableOut << 10) | xorreduce(tx3nt(a, 2), 32, 10)
    result    = tx3nt(amix, 1)
    return result
