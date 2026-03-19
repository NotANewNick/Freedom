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

inf_mask_length = 0x0001
inf_mask_dev_name = 0x0002
inf_mask_dev_type = 0x0004
inf_mask_pr_ver = 0x0008

inf_mask_fm_ver = 0x0010
inf_mask_send_t = 0x0020
inf_mask_cn = 0x0040

inf_mask_ver = 0x0100
inf_mask_ios = 0x0200
inf_mask_gps = 0x0400
inf_mask_bat = 0x0800

inf_mask_cid = 0x1000
inf_mask_csq = 0x2000
inf_mask_tmz = 0x4000
inf_mask_gir = 0x8000

inf_mask_non = 0xffff

inf_msg_type_str = { 0: 'UNK0', 1: 'INF', 2: 'GPS', 3: 'UNK3', 4: 'CID',
                     5: 'CSQ',  6: 'VER', 7: 'BAT', 8: 'IOS',  9: 'TMZ',
                    10: 'GIR'}

gir_type_str = {0: 'UNK0', 1: 'SOS', 2: 'RTL', 3: 'LBC', 4: 'TOW',
                5: 'FRI',  6: 'GIR'}

def is_inf_inf(msg_type):
    return inf_msg_type_str[msg_type] == 'INF'

def is_inf_gps(msg_type):
    return inf_msg_type_str[msg_type] == 'GPS'

def is_inf_cid(msg_type):
    return inf_msg_type_str[msg_type] == 'CID'

def is_inf_csq(msg_type):
    return inf_msg_type_str[msg_type] == 'CSQ'

def is_inf_ver(msg_type):
    return inf_msg_type_str[msg_type] == 'VER'

def is_inf_bat(msg_type):
    return inf_msg_type_str[msg_type] == 'BAT'

def is_inf_ios(msg_type):
    return inf_msg_type_str[msg_type] == 'IOS'

def is_inf_tmz(msg_type):
    return inf_msg_type_str[msg_type] == 'TMZ'

def is_inf_gir(msg_type):
    return inf_msg_type_str[msg_type] == 'GIR'

class CellInfo:
    def __init__(self, mcc, mnc, lac, cell_id, ta):
        self.mcc = mcc
        self.mnc = mnc
        self.lac = lac
        self.cell_id = cell_id
        self.ta = ta

class CellInfoRx(CellInfo):
    def __init__(self, mcc, mnc, lac, cell_id, ta, rx):
        CellInfo.__init__(self, mcc, mnc, lac, cell_id, ta)
        self.rx = rx

#########################################
# External Interface
#########################################
def parse_aschex_report_inf(aschex_report):
    asc_report = []
    len_parsed = 0
    mask = 0
    msg_arg = {}
    cellrxs = []
    dbg_on = get_dbg_on()
    try:
        inf_frame = aschex_report
        #print '+INF:', inf_frame

        len_parsed += get_header(asc_report, inf_frame[len_parsed:])[1]

        dbg_print(dbg_on, 'msg_type: %s' % inf_frame[len_parsed:])
        inf_type, len_field = get_msg_type(asc_report, inf_frame[len_parsed:], inf_msg_type_str)
        len_parsed += len_field
        msg_arg['msg_type'] = inf_type

        dbg_print(dbg_on, 'mask : %s' % inf_frame[len_parsed:])
        mask, len_field = get_inf_mask(asc_report, inf_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['mask'] = mask
        #print 'mask: 0x%02X' % mask

        if mask & inf_mask_length:
            dbg_print(dbg_on, 'length: %s' % inf_frame[len_parsed:])
            length, len_field = get_length(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['length'] = length

        if mask & inf_mask_dev_name:
            dbg_print(dbg_on, 'dev_name: %s' % inf_frame[len_parsed:])
            dev_name, len_field = get_dev_name(asc_report, inf_frame[len_parsed:])
            msg_arg['dev_name'] = dev_name
        else:
            dbg_print(dbg_on, 'imei: %s' % inf_frame[len_parsed:])
            uid, len_field = get_uid(asc_report, inf_frame[len_parsed:])
            msg_arg['uid'] = uid
        len_parsed += len_field

        if mask & inf_mask_dev_type:
            dbg_print(dbg_on, 'dev_type: %s' % inf_frame[len_parsed:])
            dev_type, len_field = get_dev_type(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dev_type'] = dev_type

        if mask & inf_mask_pr_ver:
            dbg_print(dbg_on, 'pr_ver: %s' % inf_frame[len_parsed:])
            pr_ver, len_field = get_protocol_version(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['pr_ver'] = pr_ver

        if mask & inf_mask_fm_ver:
            dbg_print(dbg_on, 'fm_ver: %s' % inf_frame[len_parsed:])
            fm_ver, len_field = get_firmware_version(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['fm_ver'] = fm_ver

        if mask & inf_mask_ver:
            dbg_print(dbg_on, 'hw_ver: %s' % inf_frame[len_parsed:])
            hw_ver, len_field = get_hardware_version(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            dbg_print(dbg_on, 'mcu_ver: %s' % inf_frame[len_parsed:])
            mcu_ver, len_field = get_mcu_version(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            dbg_print(dbg_on, 'gps_ver: %s' % inf_frame[len_parsed:])
            gps_ver, len_field = get_gps_version(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['hw_ver'] = hw_ver
            msg_arg['mcu_ver'] = mcu_ver
            msg_arg['gps_ver'] = gps_ver

        if mask & inf_mask_ios:
            dbg_print(dbg_on, 'ios: %s' % inf_frame[len_parsed:])
            len_field = get_reserved_para(asc_report, inf_frame[len_parsed:], 12)
            len_parsed += len_field
            dis, len_field = get_input(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dis'] = dis
            dos, len_field = get_output(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dos'] = dos
            len_field = get_reserved_para(asc_report, inf_frame[len_parsed:], 1)
            len_parsed += len_field

        if mask & inf_mask_gps:
            dbg_print(dbg_on, 'gps: %s' % inf_frame[len_parsed:])
            motion_status, len_field = get_motion_state(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['motion_status'] = motion_status
            len_field = get_reserved_para(asc_report, inf_frame[len_parsed:], 1)
            len_parsed += len_field
            gps_scnt, len_field = get_gps_scnt(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['gps_scnt'] = gps_scnt
            gps_flag, len_field = get_gps_flag(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['gps_flag'] = gps_flag
            last_fix_t, len_field = get_last_fix_utc(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['last_fix_t'] = last_fix_t
            hdop, len_field = get_hdop(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['hdop'] = hdop
            fri_no_fix, len_field = get_fri_no_fix(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['fri_no_fix'] = fri_no_fix
            report_item_mask, len_field = get_report_item_mask(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['report_item_mask'] = report_item_mask
            ign_interval, len_field = get_igs_interval(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['ign_interval'] = ign_interval
            igf_interval, len_field = get_igs_interval(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['igf_interval'] = igf_interval
            len_field = get_reserved_para(asc_report, inf_frame[len_parsed:], 5)
            len_parsed += len_field

        if mask & inf_mask_bat:
            dbg_print(dbg_on, 'bat: %s' % inf_frame[len_parsed:])
            bat_flag, len_field = get_bat_flag(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['bat_flag'] = bat_flag
            dbg_print(dbg_on, 'eps_vcc: %s' % inf_frame[len_parsed:])
            eps_vcc, len_field = get_eps_vcc(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['eps_vcc'] = eps_vcc
            dbg_print(dbg_on, 'bat_vcc: %s' % inf_frame[len_parsed:])
            bat_vcc, len_field = get_bat_vcc(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['bat_vcc'] = bat_vcc
            dbg_print(dbg_on, 'bat_level: %s' % inf_frame[len_parsed:])
            bat_level, len_field = get_battery_level(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['bat_level'] = bat_level

        if mask & inf_mask_cid:
            dbg_print(dbg_on, 'cid: %s' % inf_frame[len_parsed:])
            iccid, len_field = get_iccid(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['iccid'] = iccid

        if mask & inf_mask_csq:
            dbg_print(dbg_on, 'csq: %s' % inf_frame[len_parsed:])
            rssi, len_field = get_rssi(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['rssi'] = rssi
            ber, len_field = get_ber(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['ber'] = ber

        if mask & inf_mask_tmz:
            dbg_print(dbg_on, 'tmz: %s' % inf_frame[len_parsed:])
            tmz_flag, len_field = get_tmz_flag(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['tmz_flag'] = tmz_flag
            tmz_offset, len_field = get_tmz_offset(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['tmz_offset'] = tmz_offset

        if mask & inf_mask_gir:
            dbg_print(dbg_on, 'gir: %s' % inf_frame[len_parsed:])
            gir_type, len_field = get_gir_type(asc_report, inf_frame[len_parsed:], gir_type_str)
            len_parsed += len_field
            msg_arg['gir_type'] = gir_type
            cell_num, len_field = get_gir_cell_num(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['cell_num'] = cell_num
            for n in range(int(cell_num)):
                cellrx_arg = {}
                dbg_print(dbg_on, 'mcc: %s' % inf_frame[len_parsed:])
                mcc, len_field = get_mcc(asc_report, inf_frame[len_parsed:])
                len_parsed += len_field
                cellrx_arg['mcc'] = mcc
                dbg_print(dbg_on, 'mnc: %s' % inf_frame[len_parsed:])
                mnc, len_field = get_mnc(asc_report, inf_frame[len_parsed:])
                len_parsed += len_field
                cellrx_arg['mnc'] = mnc
                dbg_print(dbg_on, 'lac: %s' % inf_frame[len_parsed:])
                lac, len_field = get_lac(asc_report, inf_frame[len_parsed:])
                len_parsed += len_field
                cellrx_arg['lac'] = lac
                dbg_print(dbg_on, 'cell_id: %s' % inf_frame[len_parsed:])
                cell_id, len_field = get_cell_id(asc_report, inf_frame[len_parsed:])
                len_parsed += len_field
                cellrx_arg['cell_id'] = cell_id
                dbg_print(dbg_on, 'ta: %s' % inf_frame[len_parsed:])
                ta, len_field = get_ta(asc_report, inf_frame[len_parsed:])
                len_parsed += len_field
                cellrx_arg['ta'] = ta
                dbg_print(dbg_on, 'rx: %s' % inf_frame[len_parsed:])
                rx, len_field = get_rx_level(asc_report, inf_frame[len_parsed:])
                len_parsed += len_field
                cellrx_arg['rx'] = rx
                cellrxs.append(CellInfoRx(**cellrx_arg))
            msg_arg['cellrxs'] = cellrxs

        if mask & inf_mask_send_t:
            dbg_print(dbg_on, 'send_t: %s' % inf_frame[len_parsed:])
            send_t, len_field = get_send_time(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['send_t'] = send_t

        if mask & inf_mask_cn:
            dbg_print(dbg_on, 'cn: %s' % inf_frame[len_parsed:])
            cn, len_field = get_count_number(asc_report, inf_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['cn'] = cn
        else:
            msg_arg['cn'] = None

        dbg_print(dbg_on, 'crc16: %s' % inf_frame[len_parsed:])
        crc16, len_field = get_checksum(asc_report, inf_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['crc16'] = crc16

        checksum = verify_crc16(inf_frame[len_header*len_byte:len_parsed], crc16)
        msg_arg['checksum'] = checksum

        tail, len_field = get_tail(asc_report, inf_frame[len_parsed:])
        len_parsed += len_field
        if tail.upper() != '0D0A':
            print ('Fail to verify tail: %s' % tail)
            raise ParseTailError

        if mask & inf_mask_length and length*len_byte != len_parsed:
            print ('Length does not match: Report: %d != Actual: %d' % (length, len_parsed/len_byte))
            raise ParseLengthError

        msg_arg['asc_report'] = asc_report
    except:
        print ('-'*40)
        print ('Parse Hex INF format error: ', sys.exc_info()[0])
        print (aschex_report)
        print (asc_report)
        traceback.print_exc() # XXX But this goes to stderr!
        print ('-'*40)
        raise ParseFormatError
    return msg_arg, len_parsed
