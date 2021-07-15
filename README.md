![CHIPYARD](https://github.com/ucb-bar/chipyard/raw/master/docs/_static/images/chipyard-logo-full.png)

# Chipyard Framework [![CircleCI](https://circleci.com/gh/ucb-bar/chipyard/tree/master.svg?style=svg)](https://circleci.com/gh/ucb-bar/chipyard/tree/master)

## Using Chipyard

To get started using Chipyard, see the documentation on the Chipyard documentation site: https://chipyard.readthedocs.io/

## What is Chipyard

Chipyard is an open source framework for agile development of Chisel-based systems-on-chip.
It will allow you to leverage the Chisel HDL, Rocket Chip SoC generator, and other [Berkeley][berkeley] projects to produce a [RISC-V][riscv] SoC with everything from MMIO-mapped peripherals to custom accelerators.
Chipyard contains processor cores ([Rocket][rocket-chip], [BOOM][boom], [CVA6 (Ariane)][cva6]), accelerators ([Hwacha][hwacha], [Gemmini][gemmini], [NVDLA][nvdla]), memory systems, and additional peripherals and tooling to help create a full featured SoC.
Chipyard supports multiple concurrent flows of agile hardware development, including software RTL simulation, FPGA-accelerated simulation ([FireSim][firesim]), automated VLSI flows ([Hammer][hammer]), and software workload generation for bare-metal and Linux-based systems ([FireMarshal][firemarshal]).
Chipyard is actively developed in the [Berkeley Architecture Research Group][ucb-bar] in the [Electrical Engineering and Computer Sciences Department][eecs] at the [University of California, Berkeley][berkeley].

## Resources

* Chipyard Documentation: https://chipyard.readthedocs.io/
* Chipyard Basics slides: https://fires.im/micro19-slides-pdf/02_chipyard_basics.pdf
* Chipyard Tutorial Exercise slides: https://fires.im/micro19-slides-pdf/03_building_custom_socs.pdf

## Need help?

* Join the Chipyard Mailing List: https://groups.google.com/forum/#!forum/chipyard
* If you find a bug, post an issue on this repo

## Contributing

* See [CONTRIBUTING.md](/CONTRIBUTING.md)

## Attribution and Chipyard-related Publications

If used for research, please cite Chipyard by the following publication:

```
@article{chipyard,
  author={Amid, Alon and Biancolin, David and Gonzalez, Abraham and Grubb, Daniel and Karandikar, Sagar and Liew, Harrison and Magyar,   Albert and Mao, Howard and Ou, Albert and Pemberton, Nathan and Rigge, Paul and Schmidt, Colin and Wright, John and Zhao, Jerry and Shao, Yakun Sophia and Asanovi\'{c}, Krste and Nikoli\'{c}, Borivoje},
  journal={IEEE Micro},
  title={Chipyard: Integrated Design, Simulation, and Implementation Framework for Custom SoCs},
  year={2020},
  volume={40},
  number={4},
  pages={10-21},
  doi={10.1109/MM.2020.2996616},
  ISSN={1937-4143},
}
```

* **Chipyard**
    * A. Amid, et al. *IEEE Micro'20* [PDF](https://ieeexplore.ieee.org/document/9099108).
    * A. Amid, et al. *DAC'20* [PDF](https://ieeexplore.ieee.org/document/9218756).

These additional publications cover many of the internal components used in Chipyard. However, for the most up-to-date details, users should refer to the Chipyard docs.

* **Generators**
    * **Rocket Chip**: K. Asanovic, et al., *UCB EECS TR*. [PDF](http://www2.eecs.berkeley.edu/Pubs/TechRpts/2016/EECS-2016-17.pdf).
    * **BOOM**: C. Celio, et al., *Hot Chips 30*. [PDF](https://www.hotchips.org/hc30/1conf/1.03_Berkeley_BROOM_HC30.Berkeley.Celio.v02.pdf).
      * **SonicBOOM (BOOMv3)**: J. Zhao, et al., *CARRV'20*. [PDF](https://carrv.github.io/2020/papers/CARRV2020_paper_15_Zhao.pdf).
    * **Hwacha**: Y. Lee, et al., *ESSCIRC'14*. [PDF](http://hwacha.org/papers/riscv-esscirc2014.pdf).
    * **Gemmini**: H. Genc, et al., *arXiv*. [PDF](https://arxiv.org/pdf/1911.09925).
* **Sims**
    * **FireSim**: S. Karandikar, et al., *ISCA'18*. [PDF](https://sagark.org/assets/pubs/firesim-isca2018.pdf).
        * **FireSim Micro Top Picks**: S. Karandikar, et al., *IEEE Micro, Top Picks 2018*. [PDF](https://sagark.org/assets/pubs/firesim-micro-top-picks2018.pdf).
        * **FASED**: D. Biancolin, et al., *FPGA'19*. [PDF](https://people.eecs.berkeley.edu/~biancolin/papers/fased-fpga19.pdf).
        * **Golden Gate**: A. Magyar, et al., *ICCAD'19*. [PDF](https://davidbiancolin.github.io/papers/goldengate-iccad19.pdf).
        * **FirePerf**: S. Karandikar, et al., *ASPLOS'20*. [PDF](https://sagark.org/assets/pubs/fireperf-asplos2020.pdf).
* **Tools**
    * **Chisel**: J. Bachrach, et al., *DAC'12*. [PDF](https://people.eecs.berkeley.edu/~krste/papers/chisel-dac2012.pdf).
    * **FIRRTL**: A. Izraelevitz, et al., *ICCAD'17*. [PDF](https://ieeexplore.ieee.org/document/8203780).
    * **Chisel DSP**: A. Wang, et al., *DAC'18*. [PDF](https://ieeexplore.ieee.org/document/8465790).
* **VLSI**
    * **Hammer**: E. Wang, et al., *ISQED'20*. [PDF](https://www.isqed.org/English/Archives/2020/Technical_Sessions/113.html).



[hwacha]:http://hwacha.org
[hammer]:https://github.com/ucb-bar/hammer
[firesim]:https://fires.im
[ucb-bar]: http://bar.eecs.berkeley.edu
[eecs]: https://eecs.berkeley.edu
[berkeley]: https://berkeley.edu
[riscv]: https://riscv.org/
[rocket-chip]: https://github.com/freechipsproject/rocket-chip
[boom]: https://github.com/riscv-boom/riscv-boom
[firemarshal]: https://github.com/firesim/FireMarshal/
[cva6]: https://github.com/openhwgroup/cva6/
[gemmini]: https://github.com/ucb-bar/gemmini
[nvdla]: http://nvdla.org/

# vcs sim

## 1.spike toolchains/riscv-tools/riscv-isa-sim
use gcc-4.8 g++-4.8 install spike ../configure --prefix=$RISCV CC=gcc-4.8 CXX=g++4.8

## 2.DRAM2 tools/DRAMSim2
use gcc-4.8 g++-4.8 install DRAMSim2 make CC=gcc-4.8 CXX=g++4.8

## 3.test sims/test/riscv-tests
use riscv64-unknown-elf-gcc (GCC) 9.2.0 compile isa empty and benchmarks

## 4 if use Verdi
in sims/vcs/Makefile add $(VCS_NOVAS_OPS) to VCS_OPTS
env:
export VERDI_HOME=$SOFT_DIR/Verdi3_L-2016.06-1
export PATH=$PATH:$VERDI_HOME/bin
export LD_LIBRARY_PATH=${VERDI_HOME}/share/PLI/VCS/LINUX64${LD_LIBRARY_PATH:+":${LD_LIBRARY_PATH}"}
vcs version and verdi version should match

# ENV(xzh) example

## 1.env-riscv-tools.sh auto-generated by build-toolchains.sh
export CHIPYARD_TOOLCHAIN_SOURCED=1
export RISCV=/home/xzh/Risc-V/chipyard/riscv-tools-install
export RISCV_TEST=$PWD/sims/test/riscv-tests
export PATH=${RISCV}/bin:${PATH}
export LD_LIBRARY_PATH=${RISCV}/lib${LD_LIBRARY_PATH:+":${LD_LIBRARY_PATH}"}

##  2.env.sh line auto-generated by init-submodules-no-riscv-tools.sh
__DIR="$(dirname "$(readlink -f "${BASH_SOURCE[0]:-${(%):-%x}}")")"
PATH=$__DIR/bin:$PATH
PATH=$__DIR/software/firemarshal:$PATH
this line below auto-generated by build-toolchains.sh
source /home/xzh/Risc-V/chipyard/env-riscv-tools.sh

## 3.myset_env.sh
source /home/xzh/synopsys_env
source /home/xzh/vivado_env
source $PWD/env-riscv-tools.sh
source $PWD/env-osd.sh

