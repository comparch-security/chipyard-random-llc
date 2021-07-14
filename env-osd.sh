# Variables for Open SoC Debug
if [ -z $OSD_ROOT ]; then
    #echo "\$OSD_ROOT is not defined."
    #echo "Set \$TOP/tools to the Open SoC Debug installation target (\$OSD_ROOT)."
    export OSD_ROOT=$PWD/tools
fi

#echo "Add opensocdebug to \$PYTHONPATH"
export PYTHONPATH=$OSD_ROOT/lib/python2.7/site-packages:$PYTHONPATH

if [ -z $LD_LIBRARY_PATH ]; then
  export LD_LIBRARY_PATH=$OSD_ROOT/lib
else
  export LD_LIBRARY_PATH=$OSD_ROOT/lib:$LD_LIBRARY_PATH
fi

export PATH=$OSD_ROOT/bin:$PATH

if [ -z $PKG_CONFIG_PATH ]; then
  export PKG_CONFIG_PATH=$OSD_ROOT/lib/pkgconfig
else
  export PKG_CONFIG_PATH=$OSD_ROOT/lib/pkgconfig:$PKG_CONFIG_PATH
fi
