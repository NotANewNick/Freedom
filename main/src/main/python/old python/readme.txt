###############################################################################
# Copyright (C) 2017 Queclink Wirless Solutions Co., Ltd.
# All Rights Reserved
#
# This code is provided by Queclink to its customers as a sample to demonstrate
# how to how to parse HEX format @Track protocol messages.
#
# Permission to use, copy and modify this code for the purpose of use or test 
# Queclink devices is hereby granted, provided that the above copyright notice, 
# this paragraph and the following paragraph appear in all copies, modifications.
#
# You must not distribute this code to others without express authority from 
# Queclink.
###############################################################################

This package includes Python codes to demonstrate how to parse HEX format @Track
protocol.
To run the demo, you will need Python 2.7 and Python module bitstring 3.1.4.

The simple parser in this package is designed for the purpose to decode the HEX 
format @Track protocol messages and output the parsed results to console in ASCII
format. Queclink provides this package as a reference for its customers to design
their own sophisticated and robust parser.

Files in this package:
1. demo and GV55 HEX protocol messages
demo.py
gv55.log

2. parsers for HEX @Track protocol messages in different categories.
parse_aschex_acc.py
parse_aschex_ack.py
parse_aschex_crd.py
parse_aschex_evt.py
parse_aschex_hbd.py
parse_aschex_inf.py
parse_aschex_rsp.py

3. helper functions
parse_aschex_util.py
parse_aschex_exception.py

4. Python CRC module
crcmod
