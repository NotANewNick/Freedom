################################################################
# Copyright (C) 2017 Queclink Wirless Solutions Co., Ltd.
# All Rights Reserved
#
# This code is provided by Queclink to its customers as a 
# sample to demonstrate how to how to parse HEX format 
# @Track protocol messages.
#
# Permission to use, copy and modify this code for the purpose
# of use or test Queclink devices is hereby granted, provided 
# that the above copyright notice, this paragraph and the 
# following paragraph appear in all copies, modifications.
#
# You must not distribute this code to others without express 
# authority from Queclink.
################################################################

import sys
import traceback
from parse_aschex_exception import *
from parse_aschex_util import *

acc_mask_length = 0x0001
acc_mask_dev_name = 0x0002
acc_mask_dev_type = 0x0004
acc_mask_pr_ver = 0x0008

acc_mask_fm_ver = 0x0010
acc_mask_send_t = 0x0020
acc_mask_cn = 0x0040

acc_mask_non = 0xffff

#########################################
# External Interface
#########################################
def parse_aschex_report_acc(aschex_report):
    asc_report = []
    len_parsed = 0
    mask = 0
    msg_arg = {}
    dbg_on = get_dbg_on()
    try:
        acc_frame = aschex_report
        #print '+ACC:', acc_frame

        len_parsed += get_header(asc_report, acc_frame[len_parsed:])[1]

        #dbg_print(dbg_on, 'mask : %s' % acc_frame[len_parsed:])
        #mask, len_field = get_crd_mask(asc_report, acc_frame[len_parsed:])
        #len_parsed += len_field
        mask = 0x006C
        msg_arg['mask'] = mask
        #print 'mask: 0x%02X' % mask

        if mask & acc_mask_length:
            dbg_print(dbg_on, 'length: %s' % acc_frame[len_parsed:])
            length, len_field = get_length(asc_report, acc_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['length'] = length

        if mask & acc_mask_dev_type:
            dbg_print(dbg_on, 'dev_type: %s' % acc_frame[len_parsed:])
            dev_type, len_field = get_dev_type(asc_report, acc_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dev_type'] = dev_type

        if mask & acc_mask_pr_ver:
            dbg_print(dbg_on, 'pr_ver: %s' % acc_frame[len_parsed:])
            pr_ver, len_field = get_protocol_version(asc_report, acc_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['pr_ver'] = pr_ver

        if mask & acc_mask_fm_ver:
            dbg_print(dbg_on, 'fm_ver: %s' % acc_frame[len_parsed:])
            fm_ver, len_field = get_firmware_version(asc_report, acc_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['fm_ver'] = fm_ver

        if mask & acc_mask_dev_name:
            dbg_print(dbg_on, 'dev_name: %s' % acc_frame[len_parsed:])
            dev_name, len_field = get_dev_name(asc_report, acc_frame[len_parsed:])
            msg_arg['dev_name'] = dev_name
        else:
            dbg_print(dbg_on, 'imei: %s' % acc_frame[len_parsed:])
            uid, len_field = get_uid(asc_report, acc_frame[len_parsed:])
            msg_arg['uid'] = uid
        len_parsed += len_field

        dbg_print(dbg_on, 'xyz_data: %s' % acc_frame[len_parsed:])
        xyz_data, len_field = get_acc_xyz_data(asc_report, acc_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['xyz_data'] = xyz_data

        if mask & acc_mask_send_t:
            dbg_print(dbg_on, 'send_t: %s' % acc_frame[len_parsed:])
            send_t, len_field = get_send_time(asc_report, acc_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['send_t'] = send_t

        if mask & acc_mask_cn:
            dbg_print(dbg_on, 'cn: %s' % acc_frame[len_parsed:])
            cn, len_field = get_count_number(asc_report, acc_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['cn'] = cn
        else:
            msg_arg['cn'] = None

        dbg_print(dbg_on, 'crc16: %s' % acc_frame[len_parsed:])
        crc16, len_field = get_checksum(asc_report, acc_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['crc16'] = crc16

        checksum = verify_crc16(acc_frame[len_header*len_byte:len_parsed], crc16)
        msg_arg['checksum'] = checksum

        tail, len_field = get_tail(asc_report, acc_frame[len_parsed:])
        len_parsed += len_field
        if tail.upper() != '0D0A':
            print ('Fail to verify tail: %s' % tail)
            raise ParseTailError

        if mask & acc_mask_length and length*len_byte != len_parsed:
            print ('Length does not match: Report: %d != Actual: %d' % (length, len_parsed/len_byte))
            raise ParseLengthError

        msg_arg['asc_report'] = asc_report
    except:
        print ('-'*40)
        print ('Parse Hex CRD format error: ', sys.exc_info()[0])
        print( aschex_report)
        print (asc_report)
        traceback.print_exc() # XXX But this goes to stderr!
        print ('-'*40)
        raise ParseFormatError
    return msg_arg, len_parsed
