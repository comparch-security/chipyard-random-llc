import random
import hashlib
import math
import zlib
import numpy as np
import pandas as pd
import scipy.stats as stats
import matplotlib.pyplot as plt
import matplotlib.style as style
import hashfun as testhash

##Congruent Test
addr_len       = 1024
groups         = addr_len*(addr_len-1)/2
congruents_all = 0
congruents_max = 0
congruents_min = 10222
for i in range(0, 1000):
    congruents      = 0
    evset           = []
    evsize          = 0
    key             = random.randint(0, (1 << 6) - 1)
    addr            = random.sample(range(0, (1 << 26) - 1), addr_len)
    for j in range(0, addr_len):
        evset.append(testhash.Hua2011WithKey(addr[j], key))
    for j in range(0, addr_len-1):
        for k in range(j+1, addr_len):
            if evset[j] == evset[k]:
                congruents = congruents +1
    congruents_all  = congruents_all + congruents
    if congruents > congruents_max:
        congruents_max = congruents
    if congruents < congruents_min:
        congruents_min = congruents
    print(i, round(1/(congruents_min/groups), 1), round(1/(congruents_all/groups/(i+1)), 1), round(1/(congruents_max/groups), 1))
exit()

##Rekeying Congruent Test
##Rekeying Congruent Test: Test HUA2011
'''

a = 0
congruents = 0
for i in range(0, 1000):
    evset           = []
    evsize          = 0
    evset_new       = []
    key_old         = 0
    key_new         = 0
    while key_old == key_new:
        key_old         = random.randint(0, (1 << 6) - 1)
        key_new         = random.randint(0, (1 << 6) - 1)
    target          = random.randint(0, (1 << (31-6)) - 1)
    target_set_old  = testhash.Hua2011WithKey(target, key_old)
    target_set_new  = testhash.Hua2011WithKey(target, key_new)
    for evsize in range(0, 1024):
        while 1 == 1:
            a = a+1
            if(target_set_old == testhash.Hua2011WithKey(a, key_old)):
                evset.append(a)
                evset_new.append(testhash.Hua2011WithKey(a, key_new))
                break
    for j in range(0, evsize-1):
        for k in range(j+1, evsize):
            if evset_new[j] == evset_new[k]:
                congruents = congruents +1
    print(i, target, target_set_old, target_set_new, congruents/(i+1), 1/(congruents/(i+1)/(evsize*(evsize-1)/2)))


##Rekeying Congruent Test: Test Maurice2015
a = 0
congruents = 0
for i in range(0, 1000):
    evset           = []
    evsize          = 0
    evset_new       = []
    key_old         = []
    key_new         = []
    for j in range(0, 6):
        key_old.append(random.randint(0, int((1 << (32 - 6)) - 1)))
        key_new.append(random.randint(0, int((1 << (32 - 6)) - 1)))
    target          = random.randint(0, (1 << (31-6)) - 1)
    target_set_old  = testhash.Maurice2015Index(target, key_old)
    target_set_new  = testhash.Maurice2015Index(target, key_new)
    for evsize in range(0, 1024):
        while 1 == 1:
            a = a+1
            if(target_set_old == testhash.Maurice2015Index(a, key_old)):
                evset.append(a)
                evset_new.append(testhash.Maurice2015Index(a, key_new))
                break
    for j in range(0, evsize-1):
        for k in range(j+1, evsize):
            if evset_new[j] == evset_new[k]:
                congruents = congruents +1
    print(i, target, target_set_old, target_set_new, congruents/(i+1), 1/(congruents/(i+1)/(evsize*(evsize-1)/2)))
'''

##Rekeying Congruent Test: Test Maurice2015Hua2011
a = 0
congruents = 0
for i in range(0, 1000):
    evset           = []
    evsize          = 0
    evset_new       = []
    key_old         = []
    key_new         = []
    table_old       = []
    table_new       = []
    for j in range(0, 6):
        key_old.append(random.randint(0, int((1 << (32 - 6)) - 1)))
        key_new.append(random.randint(0, int((1 << (32 - 6)) - 1)))
    '''
    for j in range(0, 64):
        table_old.append(j)
        table_new.append(j)
    random.shuffle(table_old)
    random.shuffle(table_new)
    '''
    for j in range(0, 64):
        table_old.append(random.randint(0, 63))
        table_new.append(random.randint(0, 63))
    print(key_old)
    print(key_new)
    print(table_old)
    print(table_new)
    target          = random.randint(0, (1 << (31-6)) - 1)
    target_set_old  = testhash.Maurice2015Hua2011WithKeyTable(target, key_old, table_old)
    target_set_new  = testhash.Maurice2015Hua2011WithKeyTable(target, key_new, table_new)
    for evsize in range(0, 1024):
        while 1 == 1:
            a = a+1
            if(target_set_old == testhash.Maurice2015Hua2011WithKeyTable(a, key_old, table_old)):
                evset.append(a)
                evset_new.append(testhash.Maurice2015Hua2011WithKeyTable(a, key_new, table_new))
                break
    for j in range(0, evsize-1):
        for k in range(j+1, evsize):
            if evset_new[j] == evset_new[k]:
                congruents = congruents +1
    print(i, target, target_set_old, target_set_new, congruents/(i+1), 1/(congruents/(i+1)/(evsize*(evsize-1)/2)))

##Avalanche Test
'''
def hamming(a, b):
    # compute and return the Hamming distance between the integers
    return bin(int(a) ^ int(b)).count("1")

for i in range(0, 1000):
    a = random.randint(0, 1 << 31)
    ##b = testhash.bitarray2int(testhash.sboxstage(testhash.int2bitarray(a, 32)))
    ##c = testhash2.sboxstage(a, 32)
    b =  testhash.tx3tx3tx3t(a)
    c =  testhash2.tx3tx3tx3t(a)
    if b != c:
        print(i, a, b, c)
        exit()

print("OK")
exit()
'''

compares         = 9
ham              = []
hamp             = []
rounds           = 10**3
results          = 0
iwidth           = 32 - 6
owidth           = 10
iopairs          = iwidth * owidth
ioflip                   = []           ##times each output bit inverts as a result of each input bit being inverted
ioflipf                  = []           ##frequently each output bit inverts as a result of each input bit being inverted
ioflipf_48_52            = []           ##pairs that fall into [49%; 51%]
ioflipf_45_55            = []           ##pairs that fall into [45%; 55%]
ioflipf_48_52_p          = []           ##percentage of pairs that fall into [49%; 51%]
ioflipf_45_55_p          = []           ##percentage of pairs that fall into [45%; 55%]
ioflipf_min              = []
ioflipf_max              = []
ioflipf_max_dev_from_50  = []
for i in range(0, compares):
    ham.append([])
    hamp.append([])
    ioflip.append([])
    ioflipf.append([])
    ioflipf_48_52.append(0)
    ioflipf_45_55.append(0)
    ioflipf_48_52_p.append(0)
    ioflipf_45_55_p.append(0)
    ioflipf_min.append(1000000)
    ioflipf_max.append(0)
    ioflipf_max_dev_from_50.append(0)
    for j in range(0, iopairs): ioflip[i].append(0)
    for j in range(0, owidth + 1): ham[i].append(0)
    start = random.randint(0, (1 << iwidth) - 1)
for i in range(0, rounds):
    a     = []
    hasha = []
    for j in range(0, compares): hasha.append([])
    start = start + 1
    a.append(start)
    for j in range(0, iwidth): a.append(a[0] ^ (1 << j)) ##flip 1 bit
    for j in range(0, iwidth + 1):
        tx3t           = testhash.tx3nt(a[j], 1)
        tx3tx3t        = testhash.tx3nt(a[j], 2)
        tx3tx3tx3t     = testhash.tx3nt(a[j], 3)
        mauricetx3t    = testhash.Maurice2015Hua2011(a[j])
        md5            = testhash.md5(a[j])
        #sha512     = testhash.sha512(a[j])
        hasha[0].append(testhash.xorreduce(tx3t,                   32, owidth))
        hasha[1].append(testhash.bitssel(tx3t,             owidth - 1,      0))
        hasha[2].append(testhash.xorreduce(tx3tx3t,                32, owidth))
        hasha[3].append(testhash.bitssel(tx3tx3t,          owidth - 1,      0))
        hasha[4].append(testhash.xorreduce(tx3tx3tx3t,             32, owidth))
        hasha[5].append(testhash.bitssel(tx3tx3tx3t,       owidth - 1,      0))
        hasha[6].append(testhash.xorreduce(mauricetx3t,            32, owidth))
        hasha[7].append(testhash.bitssel(mauricetx3t,      owidth - 1,      0))
        #hasha[2].append(testhash.xorreduce(md5,                   32, owidth))
        hasha[8].append(testhash.bitssel(md5,              owidth - 1,      0))
        ##hasha[4].append(testhash.xorreduce(sha512,               32, owidth))
        ##hasha[5].append(testhash.bitssel(sha512,         owidth - 1,      0))
    for j in range(0, compares):
        for k in range(1, len(hasha[j])):
            hjh0xored        = int(hasha[j][k]) ^ int(hasha[j][0])
            hamdistance      = 0
            for l in range(0, owidth):
                iopairid                  = (k-1)*owidth+l
                if testhash.bitssel(hjh0xored, l, l) == 1:
                    hamdistance           = hamdistance + 1
                    ioflip[j][iopairid]   = ioflip[j][iopairid] + 1
            ham[j][hamdistance]   = ham[j][hamdistance] + 1
            if j == 0: results = results + 1
    if testhash.bitssel(i, 12, 0) == 0: print("rounds", i)

print("results", results)
for i in range(0, compares):
    for j in range(0, owidth + 1):      hamp[i].append(ham[i][j]/results)
    for j in range(0, iopairs): ioflipf[i].append(ioflip[i][j]/rounds)
    for j in range(0, iopairs):
        if 0.48 <= ioflipf[i][j] <= 0.52: ioflipf_48_52[i] = ioflipf_48_52[i] + 1
        if 0.45 <= ioflipf[i][j] <= 0.55: ioflipf_45_55[i] = ioflipf_45_55[i] + 1
        dev_from_50 =  ioflipf[i][j] - 0.5
        if ioflipf[i][j] < ioflipf_min[i]: ioflipf_min[i] = ioflipf[i][j]
        if ioflipf[i][j] > ioflipf_max[i]: ioflipf_max[i] = ioflipf[i][j]
        if dev_from_50   < 0:  dev_from_50 = - dev_from_50
        if dev_from_50   > ioflipf_max_dev_from_50[i]: ioflipf_max_dev_from_50[i] = dev_from_50
    ioflipf_48_52_p[i] = ioflipf_48_52[i] / iopairs
    ioflipf_45_55_p[i] = ioflipf_45_55[i] / iopairs
print("hamdistance0(%)           ", [float(format(x*100, '.8')) for x in [y[0] for y in hamp]])
print("ioflipf_48_52_p(%)        ", [float(format(x*100, '.4')) for x in ioflipf_48_52_p])
print("ioflipf_45_55_p(%)        ", [float(format(x*100, '.4')) for x in ioflipf_45_55_p])
print("ioflipf_min(%)            ", [float(format(x*100, '.4')) for x in ioflipf_min])
print("ioflipf_max(%)            ", [float(format(x*100, '.4')) for x in ioflipf_max])
print("ioflipf_max_dev_from_50(%)", [float(format(x*100, '.4')) for x in ioflipf_max_dev_from_50])
x = []
for i in range(0, owidth + 1): x.append(i)
plt.plot(x, hamp[0], 'r-x', label='tx3t_xor')
plt.plot(x, hamp[1], 'r-^', label='tx3t_low')
##plt.plot(x, hamp[2], 'g-x', label='tx3tx3t_xor')
plt.plot(x, hamp[3], 'g-^', label='tx3tx3t_low')
##plt.plot(x, hamp[4], 'y-x', label='tx3tx3tx3t_xor')
##plt.plot(x, hamp[5], 'y-^', label='tx3tx3tx3t_low')
plt.plot(x, hamp[6], 'k-x', label='mauricetx3t_xor')
plt.plot(x, hamp[7], 'k-^', label='mauricetx3t_low')
plt.plot(x, hamp[8], 'b-o', label='md5_low')
plt.legend()
##for i in range(0, owidth + 1): plt.text(x[i], hamp[i], hamp[i], fontdict = None)
plt.show()
##input()
exit()


## Generalized Uniformity Test 
compares           = 9 ##4*2
K                  = 3
N                  = 10 ** 3
Bins               = 2 ** K
rounds             = 100 ##M
observed           = []
expected           = []
pvalue             = []
pmax               = []
pmean              = []
pworst             = []
log1minusp         = []  ## log10(1 - p)
log1minuspworst    = []
log1minuspmean     = []

def getS(o, e):
    s = 0
    for i in range(0, len(o)):
        s = s + ((o[i] - e[i]) ** 2)/e[i]
    return s

   
for j in range(0, compares):
    pvalue.append([])
    log1minusp.append([])
    observed.append([])
    expected.append([])
    for k in range(0, Bins):
        observed[j].append(0)
        expected[j].append(N / Bins)

plt.style.use("ggplot")
plt.rcParams['axes.unicode_minus'] = False
plt.rcParams['font.sans-serif']=['SimHei']
##df = pd.DataFrame(np.random.rand(16).reshape(1, rounds) * 100
##                  columns = ['md5'])
df = pd.DataFrame()
##df.boxplot()
plt.show()


for i in range(0, rounds):
    print("rounds:", i)
    for j in range(0, compares):
        for k in range(0, Bins):
            observed[j][k] = 0
    hashin = random.randint(1, (1 << 31 - 6) - 1)
    for j in range(0, N):
        binid  = []
        for k in range(0, compares):
            binid.append(0)
        hashin   = hashin + 10
        tx3t           = testhash.tx3nt(hashin, 1)
        tx3tx3t        = testhash.tx3nt(hashin, 2)
        tx3tx3tx3t     = testhash.tx3nt(hashin, 3)
        mauricetx3t    = testhash.Maurice2015Hua2011(hashin)
        md5            = testhash.md5(hashin)
        ##crc32        = testhash.crc32(hashin)
        ##sha512       = testhash.sha512(hashin)
        binid[0]       = testhash.xorreduce(tx3t,              32, K)
        binid[1]       = testhash.bitssel(tx3t,             K - 1, 0)
        binid[2]       = testhash.xorreduce(tx3tx3t,           32, K)
        binid[3]       = testhash.bitssel(tx3tx3t,          K - 1, 0)
        binid[4]       = testhash.xorreduce(tx3tx3tx3t,        32, K)
        binid[5]       = testhash.bitssel(tx3tx3tx3t,       K - 1, 0)
        binid[6]       = testhash.xorreduce(mauricetx3t,       32, K)
        binid[7]       = testhash.bitssel(mauricetx3t,      K - 1, 0)
        binid[8]       = testhash.bitssel(md5,              K - 1, 0)
        for k in range(0, compares):
            observed[k][binid[k]] =  observed[k][binid[k]] + 1
    for j in range(0, compares):
        p = stats.chisquare(f_obs = observed[j], f_exp = expected[j])[1]
        log1minusp_temp = np.log10(1 - p)
        pvalue[j].append(p)
        log1minusp[j].append(log1minusp_temp)

    #df.append({'aa' : log1minusp}, ignore_index = True)
df['tx3t_xor']            = log1minusp[0]
df['tx3t_low']            = log1minusp[1]
df['tx3tx3t_xor']         = log1minusp[2]
df['tx3tx3t_low']         = log1minusp[3]
df['tx3tx3tx3t_xor']      = log1minusp[4]
df['tx3tx3tx3t_low']      = log1minusp[5]
df['mauricetx3t_xor']     = log1minusp[6]
df['mauricetx3t_low']     = log1minusp[7]
df['md5_low']             = log1minusp[8]
##print(log1minusp[3])
df.boxplot()
plt.style.use("ggplot")
plt.show()


##P_value = 1 - stats.chi2.cdf(x = getS(observed, expected), df=5)


