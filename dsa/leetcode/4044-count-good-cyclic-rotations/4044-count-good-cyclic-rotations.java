class Solution {
    public int countGoodRotations(int[] nums) {
        int n = nums.length;
        int windowSize = n/2;
        long diff = 0;
        for(int i = 0 ; i < windowSize ; i++){
            diff += (nums[i+windowSize] - nums[i]);
        }
        int res = 0;
        System.out.println(diff);
        for(int i = windowSize;  i < n ; i++){
            diff += 2*(nums[i-windowSize]-nums[i]);
            res += (diff != 0)?1:0;
            System.out.println(diff);
        }
        return res;
    }
}