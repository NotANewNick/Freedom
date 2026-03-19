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


ack_mask_length = 0x01
ack_mask_dev_type = 0x02
ack_mask_pr_ver = 0x04
ack_mask_fm_ver = 0x08
ack_mask_dev_name = 0x10
ack_mask_send_t = 0x20
ack_mask_cn = 0x40
ack_mask_non = 0xff

ack_msg_type_str = { 0: 'BSI',    1: 'SRI',    2: 'QSS',    3: 'UNK3',   4: 'CFG',
                     5: 'TOW',    6: 'EPS',    7: 'DIS',    8: 'OUT',    9: 'IOB',
                    10: 'TMA',   11: 'FRI',   12: 'GEO',   13: 'SPD',   14: 'SOS',
                    15: 'UNK15', 16: 'RTO',   17: 'UNK17', 18: 'UNK18', 19: 'UNK19',
                    20: 'UNK20', 21: 'UPD',   22: 'PIN',   23: 'UNK23', 24: 'OWH',
                    25: 'DOG',   26: 'UNK26', 27: 'JDC',   28: 'IDL',   29: 'HBM',
                    30: 'HMC',   31: 'UNK31', 32: 'UNK32', 33: 'UNK33', 34: 'WLT',
                    35: 'HRM',   36: 'CRA',   37: 'UNK37', 38: 'PDS',   39: 'BZA',
                    40: 'SPA',   41: 'SSR',   42: 'UNK42', 43: 'GPJ',   44: 'RMD',
                    45: 'FFC',   46: 'CMD',   47: 'UDF',   48: 'JBS',   49: 'UNK49',
                    50: 'UNK50', 51: 'PEO',   52: 'UPC',
                    65: 'GAM'}

rto_sub_id_str =  {0: 'GPS',    1: 'RTL',    2: 'READ', 3: 'REBOOT',  4: 'RESET',
                   5: 'PWROFF', 6: 'CID',    7: 'CSQ',  8: 'VER',     9: 'BAT',
                  10: 'IOS',   11: 'TMZ',   12: 'GIR', 13: 'DELBUF', 14: 'UNKE',
                  15: 'RJB',   16: 'UNK10', 17: 'BAK'}

def is_rto_ack(ack_type):
    return ack_msg_type_str[ack_type] == 'RTO'

#########################################
# External Interface
#########################################
def parse_aschex_report_ack(aschex_report):
    asc_report = []
    len_parsed = 0
    mask = 0
    msg_arg = {}
    dbg_on = get_dbg_on()
    try:
        ack_frame = aschex_report
        #print '+ACK:', ack_frame

        len_parsed += get_header(asc_report, ack_frame[len_parsed:])[1]

        dbg_print(dbg_on, 'msg_type: %s' % ack_frame[len_parsed:])
        ack_type, len_field = get_msg_type(asc_report, ack_frame[len_parsed:], ack_msg_type_str)
        len_parsed += len_field
        msg_arg['msg_type'] = ack_type

        dbg_print(dbg_on, 'mask : %s' % ack_frame[len_parsed:])
        mask, len_field = get_ack_mask(asc_report, ack_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['mask'] = mask
        #print 'mask: 0x%02X' % mask

        if mask & ack_mask_length:
            dbg_print(dbg_on, 'length: %s' % ack_frame[len_parsed:])
            length, len_field = get_ack_length(asc_report, ack_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['length'] = length

        if mask & ack_mask_dev_type:
            dbg_print(dbg_on, 'dev_type: %s' % ack_frame[len_parsed:])
            dev_type, len_field = get_dev_type(asc_report, ack_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dev_type'] = dev_type

        if mask & ack_mask_pr_ver:
            dbg_print(dbg_on, 'pr_ver: %s' % ack_frame[len_parsed:])
            pr_ver, len_field = get_protocol_version(asc_report, ack_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['pr_ver'] = pr_ver

        if mask & ack_mask_fm_ver:
            dbg_print(dbg_on, 'fm_ver: %s' % ack_frame[len_parsed:])
            fm_ver, len_field = get_firmware_version(asc_report, ack_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['fm_ver'] = fm_ver

        if mask & ack_mask_dev_name:
            dbg_print(dbg_on, 'dev_name: %s' % ack_frame[len_parsed:])
            dev_name, len_field = get_dev_name(asc_report, ack_frame[len_parsed:])
            msg_arg['dev_name'] = dev_name
        else:
            dbg_print(dbg_on, 'imei: %s' % ack_frame[len_parsed:])
            hex_string = ack_frame[len_parsed:]
            decimal_values = [int(hex_string[i:i + 2], 16) for i in range(0, len(hex_string), 2)]
            dbg_print(dbg_on, 'imei in decimal: %s' % decimal_values)
            uid, len_field = get_uid(asc_report, ack_frame[len_parsed:])
            msg_arg['uid'] = uid
        len_parsed += len_field

        dbg_print(dbg_on, 'sub_id: %s' % ack_frame[len_parsed:])
        if is_rto_ack(ack_type): #RTO
            sub_id_str = rto_sub_id_str
        else:
            sub_id_str = None
        sub_id, len_field = get_ack_sub_id(asc_report, ack_frame[len_parsed:], sub_id_str)
        len_parsed += len_field
        msg_arg['sub_id'] = sub_id

        dbg_print(dbg_on, 'serial: %s' % ack_frame[len_parsed:])
        sn, len_field = get_ack_serial_number(asc_report, ack_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['sn'] = sn

        if mask & ack_mask_send_t:
            dbg_print(dbg_on, 'send_t: %s' % ack_frame[len_parsed:])
            send_t, len_field = get_send_time(asc_report, ack_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['send_t'] = send_t

        if mask & ack_mask_cn:
            dbg_print(dbg_on, 'cn: %s' % ack_frame[len_parsed:])
            cn, len_field = get_count_number(asc_report, ack_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['cn'] = cn
        else:
            msg_arg['cn'] = None

        dbg_print(dbg_on, 'crc16: %s' % ack_frame[len_parsed:])
        crc16, len_field = get_checksum(asc_report, ack_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['crc16'] = crc16

        checksum = verify_crc16(ack_frame[len_header*len_byte:len_parsed], crc16)
        msg_arg['checksum'] = checksum

        tail, len_field = get_tail(asc_report, ack_frame[len_parsed:])
        len_parsed += len_field
        if tail.upper() != '0D0A':
            print ('Fail to verify tail: %s' % tail)
            raise ParseTailError

        if mask & ack_mask_length and length*len_byte != len_parsed:
            print ('Length does not match: Report: %d != Actual: %d' % (length, len_parsed/len_byte))
            raise ParseLengthError

        msg_arg['asc_report'] = asc_report
    except:
        print ('-'*40)
        print ('Parse Hex ACK format error: ', sys.exc_info()[0])
        print (aschex_report)
        print (asc_report)
        traceback.print_exc() # XXX But this goes to stderr!
        print ('-'*40)
        raise ParseFormatError
    return msg_arg, len_parsed
