class Solution {
public:
    int longestSubsequence(vector<int>& nums) {
        int xorS = 0, nonZero = 0;
        for(int i : nums) {
            xorS ^= i;
            if(i != 0) nonZero = i;
        }
        int n = size(nums);
        if(xorS == 0) return (nonZero == 0)?0:n-1;
        return n;
    }
};