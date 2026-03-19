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

rsp_mask_speed = 0x00000001
rsp_mask_heading = 0x00000002
rsp_mask_altitude = 0x00000004
rsp_mask_cell_id = 0x00000008

rsp_mask_send_t = 0x00000010
rsp_mask_cn = 0x00000020
rsp_mask_dev_name = 0x00000040
rsp_mask_length = 0x00000080

rsp_mask_dev_type = 0x00000100
rsp_mask_pr_ver = 0x00000200
rsp_mask_fm_ver = 0x00000400
rsp_mask_battery_level = 0x00000800

rsp_mask_eps_vcc = 0x00001000

rsp_mask_io = 0x00020000
rsp_mask_motion_status = 0x00040000
rsp_mask_gps_info = 0x00080000

rsp_mask_current_mileage = 0x00100000
rsp_mask_total_mileage = 0x00200000
rsp_mask_current_hmc = 0x00400000
rsp_mask_total_hmc = 0x00800000

rsp_mask_non = 0xffffffff

rsp_msg_type_str = {0  : 'PNL', 1  : 'TOW', 2  : 'UNK2', 3  : 'LBC',    4 : 'EPS',
                    5  : 'DIS', 6  : 'IOB', 7  : 'FRI',  8  : 'GEO',    9 : 'SPD',
                    10 : 'SOS', 11 : 'RTL', 12 : 'DOG',  13 : 'UNK13', 14 : 'UNK14',
                    15 : 'HBM', 16 : 'IGL',
                    25: 'GIN',  26: 'GOT'}

def is_rsp_lbc(msg_type):
    return rsp_msg_type_str[msg_type] == 'LBC'

def is_rsp_sos(msg_type):
    return rsp_msg_type_str[msg_type] == 'SOS'

def is_rsp_gex(msg_type):
    return (rsp_msg_type_str[msg_type] == 'GIN' or rsp_msg_type_str[msg_type] == 'GOT')

class Position:
    def __init__(self, accuracy, longitude, latitude, gps_utc_t,
                 speed=None, heading=None, altitude=None,
                 mcc=None, mnc=None, lac=None, cell_id=None, ta=None):
        self.accuracy = accuracy
        self.speed = speed
        self.heading = heading
        self.altitude = altitude
        self.longitude = longitude
        self.latitude = latitude
        self.gps_utc_t = gps_utc_t
        self.mcc = mcc
        self.mnc = mnc
        self.lac = lac
        self.cell_id = cell_id
        self.ta = ta

#########################################
# External Interface
#########################################
def parse_aschex_report_rsp(aschex_report):
    asc_report = []
    len_parsed = 0
    mask = 0
    positions = []
    msg_arg = {}
    dbg_on = get_dbg_on()
    try:
        rsp_frame = aschex_report
        #print '+RSP:', rsp_frame

        len_parsed += get_header(asc_report, rsp_frame[len_parsed:])[1]

        dbg_print(dbg_on, 'rsp_type: %s' % rsp_frame[len_parsed:])
        rsp_type, len_field = get_msg_type(asc_report, rsp_frame[len_parsed:], rsp_msg_type_str)
        len_parsed += len_field
        msg_arg['msg_type'] = rsp_type

        dbg_print(dbg_on, 'mask: %s' % rsp_frame[len_parsed:])
        mask, len_field = get_mask(asc_report, rsp_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['mask'] = mask
        #print 'mask: 0x%04X' % mask

        if mask & rsp_mask_length:
            dbg_print(dbg_on, 'length: %s' % rsp_frame[len_parsed:])
            rsp_length, len_field = get_length(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['length'] = rsp_length

        if mask & rsp_mask_dev_type:
            dbg_print(dbg_on, 'dev_type: %s' % rsp_frame[len_parsed:])
            dev_type, len_field = get_dev_type(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dev_type'] = dev_type

        if mask & rsp_mask_pr_ver:
            dbg_print(dbg_on, 'pr_ver: %s' % rsp_frame[len_parsed:])
            pr_ver, len_field = get_protocol_version(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['pr_ver'] = pr_ver

        if mask & rsp_mask_fm_ver:
            dbg_print(dbg_on, 'fm_ver: %s' % rsp_frame[len_parsed:])
            fm_ver, len_field = get_firmware_version(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['fm_ver'] = fm_ver

        if mask & rsp_mask_dev_name:
            dbg_print(dbg_on, 'dev_name: %s' % rsp_frame[len_parsed:])
            dev_name, len_field = get_dev_name(asc_report, rsp_frame[len_parsed:])
            msg_arg['dev_name'] = dev_name
            uid = dev_name
        else:
            dbg_print(dbg_on, 'imei: %s' % rsp_frame[len_parsed:])
            imei, len_field = get_uid(asc_report, rsp_frame[len_parsed:])
            msg_arg['uid'] = imei
            uid = imei
        len_parsed += len_field

        if mask & rsp_mask_battery_level:
            dbg_print(dbg_on, 'battery_level: %s' % rsp_frame[len_parsed:])
            battery_level, len_field = get_battery_level(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['battery_level'] = battery_level

        if mask & rsp_mask_eps_vcc:
            dbg_print(dbg_on, 'eps_vcc: %s' % rsp_frame[len_parsed:])
            eps_vcc, len_field = get_eps_vcc(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['eps_vcc'] = eps_vcc

        if mask & rsp_mask_io:
            dbg_print(dbg_on, 'dis: %s' % rsp_frame[len_parsed:])
            dis, len_field = get_input(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dis'] = dis
            dbg_print(dbg_on, 'dos: %s' % rsp_frame[len_parsed:])
            dos, len_field = get_output(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dos'] = dos

        if mask & rsp_mask_motion_status:
            dbg_print(dbg_on, 'motion_status: %s' % rsp_frame[len_parsed:])
            motion_status, len_field = get_motion_state(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['motion_status'] = motion_status

        if mask & rsp_mask_gps_info:
            dbg_print(dbg_on, 'gps_info: %s' % rsp_frame[len_parsed:])
            gps_scnt, len_field = get_gps_scnt(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['gps_scnt'] = gps_scnt

        if is_rsp_gex(rsp_type): #GEX
            dbg_print(dbg_on, 'gex: %s' % rsp_frame[len_parsed:])
            (gex_area_type, gex_group_mask, gex_group), len_field = get_gex_group(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['gex_area_type'] = gex_area_type
            msg_arg['gex_group_mask'] = gex_group_mask
            msg_arg['gex_group'] = gex_group
        else:
            dbg_print(dbg_on, 'rsp_id_type: %s' % rsp_frame[len_parsed:])
            (report_id, report_type), len_field = get_report_id_type(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['report_id'] = report_id
            msg_arg['report_type'] = report_type

        #RSP Frame with Data
        if is_rsp_lbc(rsp_type): #LBC
            dbg_print(dbg_on, 'lbc_phone: %s' % rsp_frame[len_parsed:])
            lbc_phone, len_field = get_lbc_phone(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['lbc_phone'] = lbc_phone

        if is_rsp_sos(rsp_type): #SOS
            dbg_print(dbg_on, 'sos: %s' % rsp_frame[len_parsed:])
            len_field = get_reserved_para(asc_report, rsp_frame[len_parsed:], 1)
            len_parsed += len_field

        dbg_print(dbg_on, 'point_number: %s' % rsp_frame[len_parsed:])
        point_number, len_field = get_point_number(asc_report, rsp_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['point_number'] = point_number

        for n in range(int(point_number)):
            pos_arg = {}

            dbg_print(dbg_on, 'accuracy: %s' % rsp_frame[len_parsed:])
            accuracy, len_field = get_accuracy(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            pos_arg['accuracy'] = accuracy

            if mask & rsp_mask_speed:
                dbg_print(dbg_on, 'speed: %s' % rsp_frame[len_parsed:])
                speed, len_field = get_speed(asc_report, rsp_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['speed'] = speed

            if mask & rsp_mask_heading:
                dbg_print(dbg_on, 'heading: %s' % rsp_frame[len_parsed:])
                heading, len_field = get_heading(asc_report, rsp_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['heading'] = heading

            if mask & rsp_mask_altitude:
                dbg_print(dbg_on, 'altitude: %s' % rsp_frame[len_parsed:])
                altitude, len_field = get_altitude(asc_report, rsp_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['altitude'] = altitude

                dbg_print(dbg_on, 'longitude: %s' % rsp_frame[len_parsed:])
                lng, len_field = get_lng(asc_report, rsp_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['longitude'] = lng

                dbg_print(dbg_on, 'latitude: %s' % rsp_frame[len_parsed:])
                lat, len_field = get_lat(asc_report, rsp_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['latitude'] = lat

                dbg_print(dbg_on, 'gps_utc_t: %s' % rsp_frame[len_parsed:])
                gps_tm, len_field = get_gps_time(asc_report, rsp_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['gps_utc_t'] = gps_tm

                if mask & rsp_mask_cell_id:
                    dbg_print(dbg_on, 'mcc: %s' % rsp_frame[len_parsed:])
                    mcc, len_field = get_mcc(asc_report, rsp_frame[len_parsed:])
                    len_parsed += len_field
                    pos_arg['mcc'] = mcc
                    dbg_print(dbg_on, 'mnc: %s' % rsp_frame[len_parsed:])
                    mnc, len_field = get_mnc(asc_report, rsp_frame[len_parsed:])
                    len_parsed += len_field
                    pos_arg['mnc'] = mnc
                    dbg_print(dbg_on, 'lac: %s' % rsp_frame[len_parsed:])
                    lac, len_field = get_lac(asc_report, rsp_frame[len_parsed:])
                    len_parsed += len_field
                    pos_arg['lac'] = lac
                    dbg_print(dbg_on, 'cell_id: %s' % rsp_frame[len_parsed:])
                    cell_id, len_field = get_cell_id(asc_report, rsp_frame[len_parsed:])
                    len_parsed += len_field
                    pos_arg['cell_id'] = cell_id
                    dbg_print(dbg_on, 'ta: %s' % rsp_frame[len_parsed:])
                    ta, len_field = get_ta(asc_report, rsp_frame[len_parsed:])
                    len_parsed += len_field
                    pos_arg['ta'] = ta
                positions.append(vars(Position(**pos_arg)))
        msg_arg['positions'] = positions

        if mask & rsp_mask_current_mileage:
            dbg_print(dbg_on, 'current_mileage: %s' % rsp_frame[len_parsed:])
            current_mileage, len_field = get_current_mileage(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['current_mileage'] = current_mileage

        if mask & rsp_mask_total_mileage:
            dbg_print(dbg_on, 'total_mileage: %s' % rsp_frame[len_parsed:])
            total_mileage, len_field = get_total_mileage(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['total_mileage'] = total_mileage

        if mask & rsp_mask_current_hmc:
            dbg_print(dbg_on, 'current_hmc: %s' % rsp_frame[len_parsed:])
            current_hmc, len_field = get_current_hmc(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['current_hmc'] = current_hmc

        if mask & rsp_mask_total_hmc:
            dbg_print(dbg_on, 'total_hmc: %s' % rsp_frame[len_parsed:])
            total_hmc, len_field = get_total_hmc(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['total_hmc'] = total_hmc

        if mask & rsp_mask_send_t:
            dbg_print(dbg_on, 'send_t: %s' % rsp_frame[len_parsed:])
            send_t, len_field = get_send_time(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['send_t'] = send_t

        if mask & rsp_mask_cn:
            dbg_print(dbg_on, 'cn: %s' % rsp_frame[len_parsed:])
            cn, len_field = get_count_number(asc_report, rsp_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['cn'] = cn
        else:
            msg_arg['cn'] = None

        dbg_print(dbg_on, 'crc16: %s' % rsp_frame[len_parsed:])
        crc16, len_field = get_checksum(asc_report, rsp_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['crc16'] = crc16

        checksum = verify_crc16(rsp_frame[len_header*len_byte:len_parsed], crc16)
        msg_arg['checksum'] = checksum

        tail, len_field = get_tail(asc_report, rsp_frame[len_parsed:])
        len_parsed += len_field
        if tail.upper() != '0D0A':
            print ('Fail to verify tail: %s' % tail)
            raise ParseTailError

        if mask & rsp_mask_length and rsp_length*len_byte != len_parsed:
            print ('Length does not match: Report: %d != Actual: %d' % (rsp_length, len_parsed/len_byte))
            raise ParseLengthError

        msg_arg['asc_report'] = asc_report
    except:
        print ('-'*40)
        print ('Parse Hex RSP format error: ', sys.exc_info()[0])
        print (aschex_report)
        print (asc_report)
        traceback.print_exc() # XXX But this goes to stderr!
        print ('-'*40)
        raise ParseFormatError
    return msg_arg, len_parsed
